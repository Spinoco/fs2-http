package spinoco.fs2.http.websocket

import spinoco.protocol.http.{HostPort, HttpRequestHeader}


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
