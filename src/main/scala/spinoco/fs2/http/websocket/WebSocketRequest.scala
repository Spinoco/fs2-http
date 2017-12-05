package spinoco.fs2.http.websocket

import spinoco.protocol.http.Uri.QueryParameter
import spinoco.protocol.http.header.Host
import spinoco.protocol.http.{HostPort, HttpMethod, HttpRequestHeader, Uri}


/**
  * Request to establish websocket connection from the client
  * @param hostPort   Host (port) of the server
  * @param header     Any Header information. Note that Method will be always GET replacing any other method configured.
  *                   Also any WebSocket Handshake headers will be overriden.
  * @param secure     True, if the connection shall be secure (wss)
  */
case class WebSocketRequest(
   hostPort: HostPort
   , header: HttpRequestHeader
   , secure: Boolean
)


object WebSocketRequest {

  def ws(host: String, port: Int, path: String, params: (String, String)*): WebSocketRequest = {
    val hostPort = HostPort(host, Some(port))
    WebSocketRequest(
      hostPort = hostPort
      , header = HttpRequestHeader(
        method = HttpMethod.GET
        , path = Uri.Path.fromUtf8String(path)
        , headers = List(
          Host(hostPort)
        )
        , query = Uri.Query(params.toList.map(QueryParameter.single _ tupled))
      )
      , secure = false
    )
  }

  def ws(host: String, path: String, params: (String, String)*): WebSocketRequest =
    ws(host, 80, path, params:_*)


  def wss(host: String, port: Int, path: String, params: (String, String)*): WebSocketRequest = {
    ws(host, port, path, params:_*).copy(secure = true)
  }

  def wss(host: String, path: String, params: (String, String)*): WebSocketRequest =
    wss(host, 443, path, params:_*)



}
