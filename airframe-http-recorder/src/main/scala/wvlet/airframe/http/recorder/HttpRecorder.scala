/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.airframe.http.recorder

import java.util.Locale

import com.twitter.finagle.builder.ClientBuilder
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.finagle.service.RetryPolicy
import com.twitter.finagle.{Http, Service}
import com.twitter.util.Future
import javax.net.ssl.SSLContext
import wvlet.airframe.http.ServerAddress
import wvlet.airframe.http.finagle.FinagleServer
import wvlet.log.LogSupport
import wvlet.log.io.IOUtil

case class HttpRecorderConfig(
    name: String = "http-recorder",
    destUri: String = "localhost",
    sessionName: String = "default",
    expirationTime: String = "1M", // Delete recorded response in a month by default
    // the folder to store response records
    dbFileName: String = "http-records",
    storageFolder: String = ".airframe/http",
    recordTableName: String = "record",
    // Specify the port to use. The default is finding an available port
    private val port: Int = -1,
    // Used for computing hash key for matching requests
    requestMatcher: HttpRequestMatcher = new HttpRequestMatcher.DefaultHttpRequestMatcher(),
    excludeHeaderForRecording: (String, String) => Boolean = HttpRecorder.defaultHeaderFilterForRecording,
    fallBackHandler: Service[Request, Response] = HttpRecorder.defaultFallBackHandler
) {
  def sqliteFilePath = s"${storageFolder}/${dbFileName}.sqlite"

  lazy val serverPort = if (port == -1) {
    IOUtil.unusedPort
  } else {
    port
  }
  lazy val destAddress = ServerAddress(destUri)
}

/**
  * Creates a proxy server for recording and replaying HTTP responses.
  * This is useful for simulate the behavior of Web services, that
  * are usually too heavy to use in an restricted environment (e.g., CI servers)
  */
object HttpRecorder extends LogSupport {

  def defaultHeaderFilterForRecording: (String, String) => Boolean = { (key: String, value: String) =>
    key.toLowerCase(Locale.ENGLISH).contains("authorization")
  }

  private def newDestClient(recorderConfig: HttpRecorderConfig): Service[Request, Response] = {
    debug(s"dest: ${recorderConfig.destAddress}")
    val clientBuilder =
      ClientBuilder()
        .stack(Http.client)
        .name("airframe-http-recorder-proxy")
        .dest(recorderConfig.destAddress.hostAndPort)
        .noFailureAccrual
        .keepAlive(true)
        .retryPolicy(RetryPolicy.tries(3, RetryPolicy.TimeoutAndWriteExceptionsOnly))

    (if (recorderConfig.destAddress.port == 443) {
       clientBuilder.tls(SSLContext.getDefault)
     } else {
       clientBuilder
     }).build()
  }

  private def newRecordStoreForRecording(recorderConfig: HttpRecorderConfig, dropSession: Boolean): HttpRecordStore = {
    val recorder = new HttpRecordStore(
      recorderConfig,
      // Delete the previous recordings for the same session name
      dropSession = dropSession
    )
    recorder
  }

  /**
    * Creates an HTTP proxy server that will return recorded responses. If no record is found, it will
    * actually send the request to the destination server and record the response.
    */
  def createRecorderProxy(
      recorderConfig: HttpRecorderConfig,
      dropExistingSession: Boolean = false
  ): HttpRecorderServer = {
    val recorder = newRecordStoreForRecording(recorderConfig, dropExistingSession)
    val server = new HttpRecorderServer(
      recorder,
      HttpRecorderServer.newRecordProxyService(recorder, newDestClient(recorderConfig))
    )
    server.start
    server
  }

  /**
    * Creates an HTTP server that will record HTTP responses.
    */
  def createRecordOnlyServer(
      recorderConfig: HttpRecorderConfig,
      dropExistingSession: Boolean = true
  ): HttpRecorderServer = {
    val recorder = newRecordStoreForRecording(recorderConfig, dropExistingSession)
    val server =
      new HttpRecorderServer(recorder, HttpRecorderServer.newRecordingService(recorder, newDestClient(recorderConfig)))
    server.start
    server
  }

  /**
    * Creates an HTTP server that returns only recorded HTTP responses.
    * If no matching record is found, use the given fallback handler.
    */
  def createReplayOnlyServer(recorderConfig: HttpRecorderConfig): FinagleServer = {
    val recorder = new HttpRecordStore(recorderConfig)
    // Return the server instance as FinagleServer to avoid further recording
    val server = new HttpRecorderServer(recorder, HttpRecorderServer.newReplayService(recorder))
    server.start
    server
  }

  /**
    * Creates an HTTP server that returns programmed HTTP responses.
    * If no matching record is found, use the given fallback handler.
    */
  def createProgrammableServer(recorderConfig: HttpRecorderConfig): HttpRecorderServer = {
    val recorder = new HttpRecordStore(recorderConfig)
    val server   = new HttpRecorderServer(recorder, HttpRecorderServer.newReplayService(recorder))
    server.start
    server
  }

  /**
    * Create an in-memory programmable server, whose recorded response will be discarded after closing the server.
    * This is useful for debugging HTTP clients
    */
  def createInMemoryServer(recorderConfig: HttpRecorderConfig): HttpRecorderServer = {
    val recorder = new HttpRecordStore(recorderConfig, inMemory = true)
    val server   = new HttpRecorderServer(recorder, HttpRecorderServer.newReplayService(recorder))
    server.start
    server
  }

  /**
    * Create an in-memory programmable server, whose recorded response will be discarded after closing the server.
    * This is useful for debugging HTTP clients.
    */
  def createInMemoryProgrammableServer: HttpRecorderServer = {
    createInMemoryServer(HttpRecorderConfig())
  }

  def defaultFallBackHandler = {
    Service.mk { request: Request =>
      val r = Response(Status.NotFound)
      r.contentString = s"${request.uri} is not found"
      Future.value(r)
    }
  }
}
