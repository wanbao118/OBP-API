package code.bankconnectors

import net.liftweb.common.Box
import scala.concurrent.ops.spawn
import code.model._
import code.model.dataAccess._
import net.liftweb.mapper.By
import net.liftweb.common.Loggable
import org.bson.types.ObjectId
import net.liftweb.util.Helpers._
import net.liftweb.util.Props
import com.mongodb.QueryBuilder
import code.metadata.counterparties.Metadata
import net.liftweb.common.Full
import com.tesobe.model.UpdateBankAccount

private object LocalConnector extends Connector with Loggable {

  def getBank(bankId : BankId): Box[Bank] =
    for{
      bank <- getHostedBank(bankId)
    } yield {
      createBank(bank)
    }

  //gets banks handled by this connector
  def getBanks : List[Bank] =
    HostedBank.findAll.map(createBank)

  def getBankAccount(bankId : BankId, accountId : String) : Box[BankAccount] = {
    for{
      bank <- getHostedBank(bankId)
      account <- bank.getAccount(accountId)
    } yield Account toBankAccount account
  }

  def getModeratedOtherBankAccount(bankId: BankId, accountID : String, otherAccountID : String)
  (moderate: OtherBankAccount => Option[ModeratedOtherBankAccount]): Box[ModeratedOtherBankAccount] = {

    /**
     * In this implementation (for legacy reasons), the "otherAccountID" is actually the mongodb id of the
     * "other account metadata" object.
     */

      for{
        objId <- tryo{ new ObjectId(otherAccountID) }
        otherAccountmetadata <- {
          //"otherAccountID" is actually the mongodb id of the other account metadata" object.
          val query = QueryBuilder.start("_id").is(objId).get()
          Metadata.find(query)
        }
      } yield{
          val otherAccountFromTransaction : OBPAccount = OBPEnvelope.find("obp_transaction.other_account.metadata",otherAccountmetadata.id.is) match {
            case Full(envelope) => envelope.obp_transaction.get.other_account.get
            case _ => {
              logger.warn("no other account found")
              OBPAccount.createRecord
            }
          }
          moderate(createOtherBankAccount(bankId, accountID, otherAccountmetadata, otherAccountFromTransaction)).get
        }
  }

  def getModeratedOtherBankAccounts(bankId: BankId, accountID : String)
  (moderate: OtherBankAccount => Option[ModeratedOtherBankAccount]): Box[List[ModeratedOtherBankAccount]] = {

    /**
     * In this implementation (for legacy reasons), the "otherAccountID" is actually the mongodb id of the
     * "other account metadata" object.
     */

    val query = QueryBuilder.start("originalPartyBankId").is(bankId.value).put("originalPartyAccountId").is(accountID).get

    val moderatedCounterparties = Metadata.findAll(query).map(meta => {
      //for legacy reasons some of the data about the "other account" are stored only on the transactions
      //so we need first to get a transaction that match to have the rest of the data
      val otherAccountFromTransaction : OBPAccount = OBPEnvelope.find("obp_transaction.other_account.holder",meta.holder.get) match {
        case Full(envelope) => {
          envelope.obp_transaction.get.other_account.get
        }
        case _ => {
          logger.warn(s"envelope not found for other account ${meta.id.get}")
          OBPAccount.createRecord
        }
      }
      moderate(createOtherBankAccount(bankId, accountID, meta, otherAccountFromTransaction))
    })

    Full(moderatedCounterparties.flatten)
  }

  def getTransactions(bankId: BankId, accountID: String, queryParams: OBPQueryParam*): Box[List[Transaction]] = {
    logger.debug("getTransactions for " + bankId + "/" + accountID)
    for{
      bank <- getHostedBank(bankId)
      account <- bank.getAccount(accountID)
    } yield {
      updateAccountTransactions(bank, account)
      account.envelopes(queryParams: _*).flatMap(createTransaction(_, account))
    }
  }

  def getTransaction(bankId: BankId, accountID : String, transactionID : String): Box[Transaction] = {
    for{
      bank <- getHostedBank(bankId) ?~! s"Transaction not found: bank $bankId not found"
      account  <- bank.getAccount(accountID) ?~! s"Transaction not found: account $accountID not found"
      objectId <- tryo{new ObjectId(transactionID)} ?~ {"Transaction "+transactionID+" not found"}
      envelope <- OBPEnvelope.find(account.transactionsForAccount.put("_id").is(objectId).get)
      transaction <- createTransaction(envelope,account)
    } yield {
      updateAccountTransactions(bank, account)
      transaction
    }
  }

  def getPhysicalCards(user : User) : Set[PhysicalCard] = {
    Set.empty
  }

  def getPhysicalCardsForBank(bankId: BankId, user : User) : Set[PhysicalCard] = {
    Set.empty
  }

  def getAccountHolders(bankId: BankId, accountID: String) : Set[User] = {
    MappedAccountHolder.findAll(
      By(MappedAccountHolder.accountBankPermalink, bankId.value),
      By(MappedAccountHolder.accountPermalink, accountID)).map(accHolder => accHolder.user.obj).flatten.toSet
  }

    private def createTransaction(env: OBPEnvelope, theAccount: Account): Option[Transaction] = {
    val transaction: OBPTransaction = env.obp_transaction.get
    val otherAccount_ = transaction.other_account.get

    val thisBankAccount = Account.toBankAccount(theAccount)
    val id = env.id.is.toString()
    val uuid = id

    //slight hack required: otherAccount id is, for legacy reasons, the mongodb id of its metadata object
    //so we have to find that
    val query = QueryBuilder.start("originalPartyBankId").is(theAccount.bankId.value).
      put("originalPartyAccountId").is(theAccount.permalink.get).
      put("holder").is(otherAccount_.holder.get).get

    Metadata.find(query) match {
      case Full(m) => {
        val otherAccount = new OtherBankAccount(
          id = m.id.get.toString,
          label = otherAccount_.holder.get,
          nationalIdentifier = otherAccount_.bank.get.national_identifier.get,
          swift_bic = None, //TODO: need to add this to the json/model
          iban = Some(otherAccount_.bank.get.IBAN.get),
          number = otherAccount_.number.get,
          bankName = otherAccount_.bank.get.name.get,
          kind = "",
          originalPartyBankId = theAccount.bankId,
          originalPartyAccountId = theAccount.permalink.get
        )
        val transactionType = transaction.details.get.kind.get
        val amount = transaction.details.get.value.get.amount.get
        val currency = transaction.details.get.value.get.currency.get
        val label = Some(transaction.details.get.label.get)
        val startDate = transaction.details.get.posted.get
        val finishDate = transaction.details.get.completed.get
        val balance = transaction.details.get.new_balance.get.amount.get
        val t =
          new Transaction(
            uuid,
            id,
            thisBankAccount,
            otherAccount,
            transactionType,
            amount,
            currency,
            label,
            startDate,
            finishDate,
            balance
          )
        Some(t)
      }
      case _ => {
        logger.warn(s"no metadata reference found for envelope ${env.id.get}")
        None
      }
    }

  }

  /**
  *  Checks if the last update of the account was made more than one hour ago.
  *  if it is the case we put a message in the message queue to ask for
  *  transactions updates
  *
  *  It will be used each time we fetch transactions from the DB. But the test
  *  is performed in a different thread.
  */

  private def updateAccountTransactions(bank: HostedBank, account: Account): Unit = {
    spawn{
      val useMessageQueue = Props.getBool("messageQueue.updateBankAccountsTransaction", false)
      val outDatedTransactions = now after time(account.lastUpdate.get.getTime + hours(1))
      if(outDatedTransactions && useMessageQueue) {
        UpdatesRequestSender.sendMsg(UpdateBankAccount(account.number.get, bank.national_identifier.get))
      }
    }
  }


  private def createOtherBankAccount(originalPartyBankId: BankId, originalPartyAccountId: String,
    otherAccount : Metadata, otherAccountFromTransaction : OBPAccount) : OtherBankAccount = {
    new OtherBankAccount(
      id = otherAccount.id.is.toString,
      label = otherAccount.holder.get,
      nationalIdentifier = otherAccountFromTransaction.bank.get.national_identifier.get,
      swift_bic = None, //TODO: need to add this to the json/model
      iban = Some(otherAccountFromTransaction.bank.get.IBAN.get),
      number = otherAccountFromTransaction.number.get,
      bankName = otherAccountFromTransaction.bank.get.name.get,
      kind = "",
      originalPartyBankId = originalPartyBankId,
      originalPartyAccountId = originalPartyAccountId
    )
  }

  private def getHostedBank(bankId : BankId) : Box[HostedBank] = {
    HostedBank.find("permalink", bankId.value) ?~ {"bank " + bankId + " not found"}
  }

  private def createBank(bank : HostedBank) : Bank = {
    new Bank(
      BankId(bank.permalink.is.toString),
      bank.alias.is,
      bank.name.is,
      bank.logoURL.is,
      bank.website.is
    )
  }
}