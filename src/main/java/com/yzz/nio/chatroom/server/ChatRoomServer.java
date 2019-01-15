package com.yzz.nio.chatroom.server;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * describe:
 * E-mail:yzzstyle@163.com  date:2019/1/15
 *
 * @Since 0.0.1
 */
public class ChatRoomServer implements Runnable {

    //选择器，负责监听客户端消息
    private Selector selector;
    //地址
    private SocketAddress socketAddress;
    //服务端Channel
    private ServerSocketChannel serverSocketChannel;
    //默认端口号
    public static final int DEFAULT_PORT = 6633;
    //log4j
    private Logger log = Logger.getLogger(this.getClass());
    //通过连接池创建的一个单线程，负责去处理服务端业务
    private ExecutorService singlePool = Executors.newSingleThreadExecutor();

    /**
     * 私有构造，外界通过start启动服务
     *
     * @param port 端口号
     * @throws IOException
     */
    private ChatRoomServer(int port) throws IOException {
        socketAddress = new InetSocketAddress(port);
        //获取ServerSocketChannel通道实例，这里仅相当于打开一个通道
        serverSocketChannel = ServerSocketChannel.open();
        //绑定地址，这一步完成之后，端口其实已经可以被访问了
        serverSocketChannel.bind(socketAddress);
        //非阻塞，Selector只接受非阻塞的对象
        serverSocketChannel.configureBlocking(false);
        //选择器开始提供服务
        selector = Selector.open();
        //将会服务端通道注册进Selector，并对OP_ACCEPT感兴趣
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
        log.info("chat room has started listening " + port);
    }

    /**
     * UnmodifiableSet 只读 selector.keys()
     * 不可grow的Set，selector.selectedKeys()
     *
     * @throws IOException
     */
    @Override
    public void run() {
        try {
            //轮询监听
            listening();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            //回收资源
            try {
                //关闭服务端通道
                if (serverSocketChannel != null) serverSocketChannel.close();
                //关闭选择器
                if (null != selector) selector.close();
                //关闭线程
                singlePool.shutdownNow();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    private void listening() throws IOException {
        //这里必须要轮询获取Selector里面的事件
        while (true) {
            int waiter = 0;
            //目前等待被处理的事件个数
            waiter = selector.select();
            if (waiter == 0) continue;
            //ungrowableSet,不能增加新的元素
            Set<SelectionKey> keys = selector.selectedKeys();
            //获取迭代器
            Iterator<SelectionKey> set = keys.iterator();
            while (set.hasNext()) {
                SelectionKey key = set.next();
                //所以，后面在线人数的统计要注意
                set.remove();
                //提供转发服务
                dispatch(key);
            }
        }
    }

    public void dispatch(SelectionKey key) {
        SocketChannel clientChannel = null;
        try {
            switch (key.interestOps()) {
                //这里是服务端在接收到客户端连接的时候触发的事件
                case SelectionKey.OP_ACCEPT:
                    //接收客户端的连接请求，并建立和客户端的连接
                    clientChannel = serverSocketChannel.accept();
                    //设置非阻塞(selector 只可以接收非阻塞的Channel)
                    clientChannel.configureBlocking(false);
                    //将和客户端交互的通道注册到Selector，对OP_READ感兴趣
                    clientChannel.register(selector, SelectionKey.OP_READ);
                    log.info("上线提醒," + "当前在线人数：" + (selector.keys().size() - 1) + "人");
                    break;
                case SelectionKey.OP_READ:
                    //通过读取客户端发送过来的消息，在进行转发
                    clientChannel = (SocketChannel) key.channel();
                    ByteBuffer byteBuffer = receiveMsg(clientChannel);
                    dispatchMsg(clientChannel, byteBuffer);
                    break;
            }
        } catch (IOException e) {
            log.warn(e.getMessage());
            //移除当前key，这里移除了可以，但是keys不会立即去移除，只有Selector被再次唤醒的时候才会被移除
            key.cancel();
            log.info("下线提醒," + "当前在线人数：" + (selector.keys().size() - 2) + "人");
        }

    }

    /**
     * 读客户端的消息
     * 这里规定服务端发送给客户端的第一个字节是标识是否是自身发送的消息
     * 1标识本人 0标识其他人
     *
     * @param client
     * @return
     * @throws IOException
     */
    public ByteBuffer receiveMsg(SocketChannel client) throws IOException {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(128);
        byteBuffer.put((byte) 0);
        client.read(byteBuffer);
        return byteBuffer;
    }

    /**
     * 转发消息
     * 标志位 1代表本人，0代表其他人
     *
     * @param client
     * @param byteBuffer
     * @throws IOException
     */
    public void dispatchMsg(SocketChannel client, ByteBuffer byteBuffer) throws IOException {
        //不可被修改
        Set<SelectionKey> keys = selector.keys();
        //这里需要转换状态为读 position = 0，limit=message.length
        byteBuffer.flip();
        for (SelectionKey key : keys) {
            SelectableChannel channel = key.channel();
            //判断是不是SocketChannel，转发是针对客户端通道而言的
            if (channel != null && channel instanceof SocketChannel) {
                SocketChannel targetClient = (SocketChannel) channel;
                //如果是本人，需要将第一位改为byte 0
                //写消息 这时候position=0，limit=1+message.length
                if (channel == client) {
                    //标志位 1代表本人，0代表其他人 此时 position=1 limit=128
                    byteBuffer.put(0, (byte) 1);
                }else {
                    byteBuffer.put(0, (byte) 0);
                }
                targetClient.write(byteBuffer);
                //重复使用
                byteBuffer.position(0);
            }
        }
    }

    /**
     * 使用默认端口
     *
     * @return
     */
    public static ChatRoomServer start() {
        return start(DEFAULT_PORT);
    }

    /**
     * 使用自定义端口
     *
     * @param port
     * @return
     */
    public static ChatRoomServer start(int port) {
        ChatRoomServer chartRoomServer = null;
        try {
            chartRoomServer = new ChatRoomServer(port);
            chartRoomServer.singlePool.execute(chartRoomServer);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return chartRoomServer;
    }

}
