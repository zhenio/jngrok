/**
 * 与Ngrokd建立控制连接，并交换控制信息
 */
package ngrok.connect;

import java.io.IOException;
import java.net.Socket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ngrok.ExitConnectException;
import ngrok.NgContext;
import ngrok.NgMsg;
import ngrok.Protocol;
import ngrok.model.Tunnel;
import ngrok.socket.PacketReader;
import ngrok.socket.SocketHelper;
import ngrok.util.GsonUtil;
import ngrok.util.Util;

public class ControlConnect implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ControlConnect.class);

    private Socket socket;
    private NgContext context;

    public ControlConnect(Socket socket, NgContext context) {
        this.socket = socket;
        this.context = context;
    }

    @Override
    public void run() {
        try (Socket socket = this.socket) {
            auth(socket);
            Protocol protocol = readProtocol(socket);
            if ("AuthResp".equals(protocol.Type)) {
                handleAuthResp(socket, protocol);
            }
        } catch (ExitConnectException e) {
            // 正常退出
            context.setStatus(NgContext.EXITED);
        } catch (Exception e) {
            // 异常退出，准备重连
            log.error(e.toString());
            context.setStatus(NgContext.PENDING);
        } finally {
            log.info("Connect exit: " + socket);
            context.clean();
        }
    }

    private void auth(Socket socket) throws IOException {
        SocketHelper.sendpack(socket, NgMsg.Auth(context.authToken));
    }

    private Protocol readProtocol(Socket socket) throws ExitConnectException, IOException {
        PacketReader pr = new PacketReader(socket, context.soTimeout);
        String msg = pr.read();
        if (msg == null) {
            // 服务器主动关闭连接，正常退出
            throw new ExitConnectException(socket);
        }
        log.info("收到服务器信息：" + msg);
        Protocol protocol = GsonUtil.toBean(msg, Protocol.class);
        return protocol;
    }

    private void handleAuthResp(Socket socket, Protocol protocol) throws ExitConnectException, IOException {
        if (Util.isNotEmpty(protocol.Payload.Error)) {
            log.error("客户端认证失败：" + protocol.Payload.Error);
            throw new ExitConnectException(socket);
        }
        String clientId = protocol.Payload.ClientId;
        log.info("客户端认证成功：" + clientId);
        context.setStatus(NgContext.AUTHERIZED);
        reqTunnel(socket);
        // 客户端注册成功，接下来处理数据代理、管道注册和心跳
        while (true) {
            protocol = readProtocol(socket);
            if ("ReqProxy".equals(protocol.Type)) {
                handleReqProxy(socket, clientId);
            } else if ("NewTunnel".equals(protocol.Type)) {
                handleNewTunnel(socket, protocol);
            } else if ("Pong".equals(protocol.Type)) {
                // do nothing
            }
        }
    }

    private void reqTunnel(Socket socket) throws IOException {
        for (Tunnel tunnel : context.tunnelList) {
            SocketHelper.sendpack(socket, NgMsg.ReqTunnel(tunnel));
        }
    }

    private void handleReqProxy(Socket socket, String clientId) throws IOException {
        try {
            Socket remoteSocket = SocketHelper.newSSLSocket(context.serverHost, context.serverPort, context.soTimeout);
            Thread thread = new Thread(new ProxyConnect(remoteSocket, clientId, context));
            thread.setDaemon(true);
            thread.start();
        } catch (Exception e) {
            log.error(e.toString());
        }
    }

    private void handleNewTunnel(Socket socket, Protocol protocol) throws ExitConnectException, IOException {
        if (Util.isNotEmpty(protocol.Payload.Error)) {
            log.error("管道注册失败：" + protocol.Payload.Error);
            throw new ExitConnectException(socket);
        }
        log.info("管道注册成功：" + protocol.Payload.Url);
    }
}
