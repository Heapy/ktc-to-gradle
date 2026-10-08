#!/usr/bin/env kotlinr
// Serve installer fixtures on loopback. Usage: kotlinr tools/http-server.main.kts ROOT READY_FILE STOP_FILE
// READY_FILE receives the bound URL; creating STOP_FILE shuts down the server.
import com.sun.net.httpserver.SimpleFileServer
import java.net.InetSocketAddress
import java.nio.file.Path
import java.io.File

require(args.size == 3) { "Usage: http-server.main.kts ROOT READY_FILE STOP_FILE" }
val root = Path.of(args[0]).toRealPath()
val ready = File(args[1])
val stop = File(args[2])
require(!ready.exists() && !stop.exists()) { "Readiness/stop files must not already exist" }
val server = SimpleFileServer.createFileServer(InetSocketAddress("127.0.0.1", 0), root, SimpleFileServer.OutputLevel.INFO)
Runtime.getRuntime().addShutdownHook(Thread { server.stop(0) })
try {
    server.start()
    ready.writeText("http://127.0.0.1:${server.address.port}")
    while (!stop.exists()) Thread.sleep(100)
} finally {
    server.stop(0)
    ready.delete()
}
