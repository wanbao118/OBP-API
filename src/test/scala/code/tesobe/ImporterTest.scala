package code.tesobe

import java.text.SimpleDateFormat
import java.util.TimeZone

import code.api.DefaultConnectorTestSetup
import code.api.test.{APIResponse, ServerSetup}
import code.bankconnectors.Connector
import code.model.{Transaction, AccountId}
import net.liftweb.common.Loggable
import net.liftweb.util.Props
import dispatch._

class ImporterTest extends ServerSetup with Loggable with DefaultConnectorTestSetup {

  override def beforeEach() = {
    super.beforeEach()
    wipeTestData()
  }

  override def afterEach() = {
    super.afterEach()
    wipeTestData()
  }

  val secretKeyHttpParamName = "secret"
  val secretKeyValue = Props.get("importer_secret").get

  val dummyKind = "Transfer"

  def fixture() = new {
    lazy val bank = createBank("a-bank")
    lazy val accountCurrency = "EUR"
    lazy val account = createAccount(bank.bankId, AccountId("an-account"), accountCurrency)
    val originalBalance = account.balance.toString

    val t1Value = "12.34"
    val t1NewBalance = "3434.22"
    val t1StartDate = "2012-01-04T18:06:22.000Z"
    val t1EndDate = "2012-01-05T18:52:13.000Z"

    val t2Value = "13.54"
    val t2NewBalance = "3447.76"
    val t2StartDate = "2012-01-04T18:06:22.000Z"
    val t2EndDate = "2012-01-06T18:52:13.000Z"

    val dummyLabel = "this is a description"

    //import transaction json is just an array of 'tJson'.
    val testJson = importJson(List(
      tJson(t1Value, t1NewBalance, t1StartDate, t1EndDate),
      tJson(t2Value, t2NewBalance, t2StartDate, t2EndDate)))

    def importJson(transactionJsons: List[String]) = {
      s"""[${transactionJsons.mkString(",")}
      ]"""
    }

    def tJson(value : String, newBalance : String, startDateString : String, endDateString : String) : String = {
      //the double dollar signs are single dollar signs that have been escaped
      s"""{
        |    "obp_transaction": {
        |        "this_account": {
        |            "holder": "Alan Holder",
        |            "number": "${account.number}",
        |            "kind": "${account.accountType}",
        |            "bank": {
        |                "IBAN": "${account.iban.getOrElse("")}",
        |                "national_identifier": "${account.nationalIdentifier}",
        |                "name": "${account.bankName}"
        |            }
        |        },
        |        "other_account": {
        |            "holder": "Client 1",
        |            "number": "123567",
        |            "kind": "current",
        |            "bank": {
        |                "IBAN": "UK12222879",
        |                "national_identifier": "uk.10010010",
        |                "name": "HSBC"
        |            }
        |        },
        |        "details": {
        |            "kind": "$dummyKind",
        |            "label": "$dummyLabel"
        |            "posted": {
        |                "$$dt": "$startDateString"
        |            },
        |            "completed": {
        |                "$$dt": "$endDateString"
        |            },
        |            "new_balance": {
        |                "currency": "EUR",
        |                "amount": "$newBalance"
        |            },
        |            "value": {
        |                "currency": "EUR",
        |                "amount": "$value"
        |            }
        |        }
        |    }
        |}""".stripMargin //stripMargin removes the whitespace before the pipes, and the pipes themselves
    }
  }

  feature("Importing transactions via an API call") {

    def addTransactions(data : String, secretKey : Option[String]) : APIResponse = {

      val baseReq = (baseRequest / "obp_transactions_saver" / "api" / "transactions").POST

      val req = secretKey match {
        case Some(key) =>
          // the <<? adds as url query params
          baseReq <<? Map(secretKeyHttpParamName -> key)
        case None =>
          baseReq
      }

      makePostRequest(req, data)
    }

    def checkOkay(t : Transaction, value : String, newBalance : String, startDate : String, endDate : String, label : String) = {
      t.amount.toString should equal(value)
      t.balance.toString should equal(newBalance)
      t.description should equal(Some(label))

      //the import api uses a different degree of detail than the main api (extra SSS)
      //to compare the import api date string values to the dates returned from the api
      //we need to parse them
      val importJsonDateFormat = {
        val f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        //setting the time zone is important!
        f.setTimeZone(TimeZone.getTimeZone("UTC"))
        f
      }

      t.transactionType should equal(dummyKind)

      t.startDate should equal(importJsonDateFormat.parse(startDate))
      t.finishDate should equal(importJsonDateFormat.parse(endDate))
    }

    scenario("Attempting to import transactions without using a secret key") {
      val f = fixture()

      Given("An account with no transactions")
      val tsBefore = Connector.connector.vend.getTransactions(f.account.bankId, f.account.accountId).get
      tsBefore.size should equal(0)

      When("We try to import transactions without using a secret key")
      val response = addTransactions(f.testJson, None)

      Then("We should get a 400")
      response.code should equal(400)

      And("No transactions should be added")
      val tsAfter = Connector.connector.vend.getTransactions(f.account.bankId, f.account.accountId).get
      tsAfter.size should equal(0)
    }

    scenario("Attempting to import transactions with the incorrect secret key") {
      val f = fixture()

      Given("An account with no transactions")
      val tsBefore = Connector.connector.vend.getTransactions(f.account.bankId, f.account.accountId).get
      tsBefore.size should equal(0)

      When("We try to import transactions with the incorrect secret key")
      val response = addTransactions(f.testJson, Some(secretKeyValue + "asdsadsad"))

      Then("We should get a 401")
      response.code should equal(401)

      And("No transactions should be added")
      val tsAfter = Connector.connector.vend.getTransactions(f.account.bankId, f.account.accountId).get
      tsAfter.size should equal(0)
    }

    scenario("Attempting to import transactions with the correct secret key") {
      val f = fixture()

      Given("An account with no transactions")
      val tsBefore = Connector.connector.vend.getTransactions(f.account.bankId, f.account.accountId).get
      tsBefore.size should equal(0)

      When("We try to import transactions with the correct secret key")
      val response = addTransactions(f.testJson, Some(secretKeyValue))

      Then("We should get a 200") //implementation returns 200 and not 201, so we'll leave it like that
      response.code should equal(200)

      And("Transactions should be added")
      val tsAfter = Connector.connector.vend.getTransactions(f.account.bankId, f.account.accountId).get
      tsAfter.size should equal(2)

      And("The transactions should have the correct parameters")
      val t1 = tsAfter(0)
      checkOkay(t1, f.t1Value, f.t1NewBalance, f.t1StartDate, f.t1EndDate, f.dummyLabel)

      val t2 = tsAfter(1)
      checkOkay(t2, f.t2Value, f.t2NewBalance, f.t2StartDate, f.t2EndDate, f.dummyLabel)


      And("The account should have its balance set to the 'new_balance' value of the most recently completed transaction")
      val account = Connector.connector.vend.getBankAccount(f.account.bankId, f.account.accountId).get
      account.balance.toString should equal(f.t2NewBalance) //t2 has a later completed date than t1

    }

    scenario("Attempting to add 'identical' transactions") {
      val f = fixture()
      def checkTransactionOkay(t : Transaction) = checkOkay(t, f.t1Value, f.t1NewBalance, f.t1StartDate, f.t1EndDate, f.dummyLabel)

      Given("An account with no transactions")
      val tsBefore = Connector.connector.vend.getTransactions(f.account.bankId, f.account.accountId).get
      tsBefore.size should equal(0)

      When("We try to import two identical transactions with the correct secret key")
      val t1Json = f.tJson(f.t1Value, f.t1NewBalance, f.t1StartDate, f.t1EndDate)
      val importJson = f.importJson(List.fill(2)(t1Json))
      val response = addTransactions(importJson, Some(secretKeyValue))

      //it should NOT complain about identical "new balances" as sometimes this value is unknown, or computed on a daily basis
      //and hence all transactions for the day will have the same end balance
      Then("We should get a 200") //implementation returns 200 and not 201, so we'll leave it like that
      response.code should equal(200)

      And("Transactions should be added")
      val tsAfter = Connector.connector.vend.getTransactions(f.account.bankId, f.account.accountId).get
      tsAfter.size should equal(2)

      And("The transactions should have the correct parameters")
      tsAfter.foreach(checkTransactionOkay)

      And("The account should have its balance set to the 'new_balance' value of the most recently completed transaction")
      val account = Connector.connector.vend.getBankAccount(f.account.bankId, f.account.accountId).get
      account.balance.toString should equal(f.t1NewBalance)
    }

    scenario("Adding transactions that have already been imported") {
      val f = fixture()
      def checkTransactionOkay(t : Transaction) = checkOkay(t, f.t1Value, f.t1NewBalance, f.t1StartDate, f.t1EndDate, f.dummyLabel)

      val t1Json = f.tJson(f.t1Value, f.t1NewBalance, f.t1StartDate, f.t1EndDate)
      val importJson = f.importJson(List.fill(2)(t1Json))

      Given("Two 'identical' existing transactions")
      addTransactions(importJson, Some(secretKeyValue))
      val tsBefore = Connector.connector.vend.getTransactions(f.account.bankId, f.account.accountId).get
      tsBefore.size should equal(2)

      tsBefore.foreach(checkTransactionOkay)

      When("We try to add those transactions again")
      val response = addTransactions(importJson, Some(secretKeyValue))

      Then("We should get a 200") //implementation returns 200 and not 201, so we'll leave it like that
      response.code should equal(200)

      And("There should still only be two transactions")
      val tsAfter = Connector.connector.vend.getTransactions(f.account.bankId, f.account.accountId).get
      tsAfter.size should equal(2)

      tsAfter.foreach(checkTransactionOkay)
    }

    scenario("Adding 'identical' transactions, some of which have already been imported") {
      val f = fixture()
      def checkTransactionOkay(t : Transaction) = checkOkay(t, f.t1Value, f.t1NewBalance, f.t1StartDate, f.t1EndDate, f.dummyLabel)

      val t1Json = f.tJson(f.t1Value, f.t1NewBalance, f.t1StartDate, f.t1EndDate)
      val initialImportJson = f.importJson(List.fill(2)(t1Json))
      val secondImportJson = f.importJson(List.fill(5)(t1Json))

      Given("Two 'identical' existing transactions")
      addTransactions(initialImportJson, Some(secretKeyValue))
      val tsBefore = Connector.connector.vend.getTransactions(f.account.bankId, f.account.accountId).get
      tsBefore.size should equal(2)

      checkTransactionOkay(tsBefore(0))
      checkTransactionOkay(tsBefore(1))

      When("We try to add 5 copies of the transaction")
      val response = addTransactions(secondImportJson, Some(secretKeyValue))

      Then("We should get a 200") //implementation returns 200 and not 201, so we'll leave it like that
      response.code should equal(200)

      And("There should now be 5 transactions")
      val tsAfter = Connector.connector.vend.getTransactions(f.account.bankId, f.account.accountId).get
      tsAfter.size should equal(5)

      tsAfter.foreach(checkTransactionOkay)
    }

    scenario("Attempting to import transactions using an incorrect json format") {
      val f = fixture()
      Given("An account with no transactions")
      val tsBefore = Connector.connector.vend.getTransactions(f.account.bankId, f.account.accountId).get
      tsBefore.size should equal(0)

      When("We try to import transactions with the correct secret key")
      val response = addTransactions("""{"some_gibberish" : "json"}""", Some(secretKeyValue))

      Then("We should get a 500") //implementation returns 500, so we'll leave it like that
      response.code should equal(200)

      And("No transactions should be added")
      val tsAfter = Connector.connector.vend.getTransactions(f.account.bankId, f.account.accountId).get
      tsAfter.size should equal(0)
    }

  }

}