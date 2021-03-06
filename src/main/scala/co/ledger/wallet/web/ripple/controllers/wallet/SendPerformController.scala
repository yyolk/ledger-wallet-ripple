package co.ledger.wallet.web.ripple.controllers.wallet

import biz.enef.angulate.Module.RichModule
import biz.enef.angulate.core.Location
import biz.enef.angulate.{Controller, Scope}
import co.ledger.wallet.core.device.Device
import co.ledger.wallet.core.device.ripple.LedgerApi
import co.ledger.wallet.core.device.ripple.LedgerCommonApiInterface.LedgerApiException
import co.ledger.wallet.core.device.utils.EventReceiver
import co.ledger.wallet.core.utils.{DerivationPath, HexUtils, Nullable}
import co.ledger.wallet.core.wallet.ripple.api.ApiAccountRestClient
import co.ledger.wallet.core.wallet.ripple.api.WebsocketRipple.{WebsocketDisconnectedEvent, WebsocketErrorEvent, WebsocketTransactionSentEvent}
import co.ledger.wallet.core.wallet.ripple.{RippleAccount, XRP}
import co.ledger.wallet.web.ripple.components.{RippleSerializer, SnackBar}
import co.ledger.wallet.web.ripple.core.net.JQHttpClient
import co.ledger.wallet.web.ripple.core.utils.ChromeGlobalPreferences
import co.ledger.wallet.web.ripple.services.{DeviceService, RippleLibApiService, SessionService, WindowService}
import co.ledger.wallet.web.ripple.wallet.RippleLibApi.LedgerEvent
import co.ledger.wallet.web.ripple.wallet.RippleWalletClient
import exceptions.{DisconnectedException, MissingTagException, RippleException}
import org.scalajs.dom
import org.scalajs.dom.CustomEvent

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js
import scala.scalajs.js.JSON
import scala.util.{Failure, Success}
import scala.scalajs.js.timers.{SetTimeoutHandle, clearTimeout, setTimeout}


/**
  *
  * SendPerformViewController
  * ledger-wallet-ripple-chrome
  *
  * Created by Pierre Pollastri on 10/05/2016.
  *
  * The MIT License (MIT)
  *
  * Copyright (c) 2016 Ledger
  *
  * Permission is hereby granted, free of charge, to any person obtaining a copy
  * of this software and associated documentation files (the "Software"), to deal
  * in the Software without restriction, including without limitation the rights
  * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
  * copies of the Software, and to permit persons to whom the Software is
  * furnished to do so, subject to the following conditions:
  *
  * The above copyright notice and this permission notice shall be included in all
  * copies or substantial portions of the Software.
  *
  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
  * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
  * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
  * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
  * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
  * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
  * SOFTWARE.
  */
class SendPerformController(override val windowService: WindowService,
                            override val $scope: Scope,
                            override val sessionService: SessionService,
                            rippleLibApiService: RippleLibApiService,
                            deviceService: DeviceService,
                            $location: Location,
                            $route: js.Dynamic,
                            $routeParams: js.Dictionary[String]) extends Controller with WalletController {
  private val fee = XRP($routeParams("fee").toInt)
  private val _ledgerOffset = 12
  var submitted = false

  case class ValidationTimeException() extends Exception("The transaction was not validated in time")

  def send() = {
    val accountId =
    $routeParams("account_id").toInt
    val amount =
    XRP($routeParams("amount"))
    val to =
    RippleAccount($routeParams("recipient").trim)
    val tag: Option[Long] = (if ($routeParams.contains("tag")) Some($routeParams("tag").toLong) else None)
    val data = $routeParams.lift("data").map(_.replace("0x", "")).map(HexUtils.decodeHex)
    val api = rippleLibApiService.api
    var rippleAccount: RippleAccount = RippleAccount("rrrrrrrrrrrrrrrrrrrrrhoLvTp")
    var derivationPath: DerivationPath = null
    var prepared: String = ""
    var deviceLocal: Device = null
    var tx: js.Dynamic = null
    windowService.disableUserInterface()
    val apiOption = rippleLibApiService.api.APIOption(server = Some(new ChromeGlobalPreferences("Settings").string("node").get))
    rippleLibApiService.init(apiOption) flatMap { (_) =>
      sessionService.currentSession.get.wallet.account(accountId) flatMap { (account) =>
        account.rippleAccount() flatMap { (rippleAccount_) =>
          rippleAccount = rippleAccount_
          account.rippleAccountDerivationPath()
        } flatMap { (derivationPath_) =>
          derivationPath = derivationPath_
          api.preparePayment(api.PaymentParam(
            rippleAccount.toString,
            api.Payment(
              api.Source(rippleAccount.toString, amount = Some(api.LaxAmount(value = Some(amount.toXRP.toString)))),
              api.Destination(to.toString, minAmount = Some(api.LaxAmount(value = Some(amount.toXRP.toString))), tag = tag)
            ),
            Some(api.Instructions(fee = /*None*/Some(fee.toXRP.toString),
              maxLedgerVersionOffset = Some(_ledgerOffset)
            ))
          )
          )
        } flatMap { (prepareResponse) =>
          prepared = prepareResponse.txJSON
          deviceService.lastConnectedDevice()
        } flatMap { (device) =>
          deviceLocal = device
          LedgerApi(device).derivePublicAddress(derivationPath)
        } flatMap { (pubAddressResult) =>
          tx = js.JSON.parse(prepared)
          tx.SigningPubKey = HexUtils.bytesToHex(pubAddressResult.publicKey).toUpperCase
          println(tx.SigningPubKey)
          val stringToSign = JSON.stringify(tx)
          js.Dynamic.global.console.log(stringToSign)
          LedgerApi(deviceLocal).signTransaction(derivationPath, stringToSign)
        } flatMap { (signed) =>
          tx.TxnSignature = HexUtils.bytesToHex(signed).toUpperCase
          val encodedTx = RippleSerializer.encode(JSON.stringify(tx))
          api.submit(new api.SubmitParam(HexUtils.bytesToHex(encodedTx)))
        }
      }
    } flatMap {(response) =>
      submitted = true
      setTimeout(0){
        $scope.$digest()
      }
      val promise = Promise[Unit]()
      var timeOut: SetTimeoutHandle = null
      val receiver: EventReceiver = new EventReceiver {
        override def receive = {
          case WebsocketTransactionSentEvent(txn) =>
            if ((txn == tx.TxnSignature)){
              clearTimeout(timeOut)
              sessionService.currentSession.get.wallet.asInstanceOf[RippleWalletClient].webSocket.get.emmiter.unregister(this)
              api.emmiter.unregister(this)
              promise.success()
            }
          case WebsocketDisconnectedEvent() =>
            clearTimeout(timeOut)
            sessionService.currentSession.get.wallet.asInstanceOf[RippleWalletClient].webSocket.get.emmiter.unregister(this)
            api.emmiter.unregister(this)
            promise.failure(DisconnectedException())
          case WebsocketErrorEvent(name, data) =>
            if (name == "tecDST_TAG_NEEDED" && data == tx.TxnSignature) {
              clearTimeout(timeOut)
              sessionService.currentSession.get.wallet.asInstanceOf[RippleWalletClient].webSocket.get.emmiter.unregister(this)
              api.emmiter.unregister(this)
              promise.failure(MissingTagException())
            }
          case LedgerEvent(e) =>
            println("New ledger:", e, tx.LastLedgerSequence)
            if (e > tx.LastLedgerSequence.asInstanceOf[Double]) {
              clearTimeout(timeOut)
              sessionService.currentSession.get.wallet.asInstanceOf[RippleWalletClient].webSocket.get.emmiter.unregister(this)
              api.emmiter.unregister(this)
              promise.failure(ValidationTimeException())
            }
          case all =>
        }
        timeOut = setTimeout(80000){
          sessionService.currentSession.get.wallet.asInstanceOf[RippleWalletClient].webSocket.get.emmiter.unregister(this)
          api.emmiter.unregister(this)
          promise.failure(new Exception("Network timed out"))
        }
      }
      api.emmiter.register(receiver)
      sessionService.currentSession.get.wallet.asInstanceOf[RippleWalletClient].webSocket.get.emmiter.register(receiver)
      promise.future
    } andThen {
      case all => rippleLibApiService.close()
    } onComplete {
      case Success(e) =>
        sessionService.currentSession.get.sessionPreferences.remove(SendIndexController.RestoreKey)
        SnackBar.success("send_perform.completed_title", "send_perform.completed_message").show()
        sessionService.currentSession.get.wallet.asInstanceOf[RippleWalletClient].synchronize()
        $location.url("/send")
        $route.reload()
      case  Failure(ex: ValidationTimeException) =>
        SnackBar.error("send_perform.expired_title", "send_perform.expired_message").show()
        $location.url("/send")
        $route.reload()
      case Failure(ex: LedgerApiException) =>
        ex.printStackTrace()
        SnackBar.error("send_perform.cancelled_title", "send_perform.cancelled_message").show()
        $location.url("/send")
        $route.reload()
      case Failure(ex: RippleException) =>
        ex.printStackTrace()
        SnackBar.error("ripple.down_title", "ripple.down_message").show()
        $location.url("/send")
        $route.reload()
      case Failure(ex: DisconnectedException) =>
        ex.printStackTrace()
        SnackBar.error("send_perform.disconnected_title", "send_perform.disconnected_message").show()
        $location.url("/send")
        $route.reload()
      case Failure(ex: MissingTagException) =>
        ex.printStackTrace()
        SnackBar.error("send_perform.missing_tag_title", "send_perform.missing_tag_message").show()
        $location.url("/send")
        $route.reload()
      case Failure(ex) =>
        ex.printStackTrace()
        SnackBar.error("send_perform.unknown_title", "send_perform.unknown_message").show()
        $location.url("/send")
        $route.reload()
    }
  }
  send()
  $scope.$on("$destroy", {() =>
    windowService.enableUserInterface()
  })
}

object SendPerformController {
  def init(module: RichModule) = module.controllerOf[SendPerformController]("SendPerformController")
}