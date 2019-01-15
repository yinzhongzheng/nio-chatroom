# IO-nio原生实现聊天室
## 概述
公司使用了netty框架做了一个在线通讯的基础框架，客户需要在线同同事进行交流，在这里我通过原生NIO api做了一个简易的聊天框架。中间遇到了很多问题，好在最后都解决了。在这里做一次记录，望共勉。
## 调用图
![在这里插入图片描述](https://img-blog.csdnimg.cn/20190115205433728.jpg?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L3FxXzIyMjcxNDc5,size_16,color_FFFFFF,t_70)
**调用顺序**
* **注意：Selector只能管理非阻塞的Channel**
1. ServerSocketChannel开启Socket监听服务
2. 创建一个Selector
3. ServerSocketChannel向Selector中注册，并表明感兴趣的事件，服务端注册的一般是OP_ACCEPT事件。
4. 轮询询问Selector是否有新的事件(SelectionKey)进来。
5. 客户端连接上服务端（TCP/IP）
6. 服务端的Selector会发出一个OP_ACCEPT事件，表明有客户端连接上了服务端，需要服务端做处理
7. 服务端接收到客户端的信息，会创建一个SocketChannel，该通道就是和客户端进行通信的，此后围绕着该通道进行read和dispatch操作即可。
8. 服务端需要将与客户端建立的通道SocketChannel托管至Selector，并表明对OP_READ事件感兴趣，客户端经由通道发送消息至服务端，Selector会出发一个OP_READ事件。
9. 在接收到OP_READ时间后，此后通过Buffer来进行读操作，这里采用DirectBuffer来减少拷贝次数。
10. 在读取完毕后，我们需要对消息做分发，通道将消息发送至客户端。到此就完成了一次响应。

## Selector的keys()和selectedKeys()剖析
```java
    protected SelectorImpl(SelectorProvider var1) {
        super(var1);
        if (Util.atBugLevel("1.4")) {
        	//都是HashSet的实例
            this.publicKeys = this.keys;
            this.publicSelectedKeys = this.selectedKeys;
        } else {
        //1.8的JDK走下面这个分支
        //不可修改（增、删、改）
            this.publicKeys = Collections.unmodifiableSet(this.keys);
            //不可增加，但是可移除
            this.publicSelectedKeys = Util.ungrowableSet(this.selectedKeys);
        }

    }
```
1. **keys()**即**selectedKeys**，一个是注册进 **Selector**的通道，是不可以修改的，对此Set的修改是根据系统通知来的，客户端离线，即连接断开，那么在下一次系统唤醒Selector的时候，会更新此Set。
2. **keys()**即keys，该Set容纳的是事件集合，系统发送至Selector的时间均在此集合，一般而言，消费过的事件需要移除，不移除的话，轮询导致消息会重复消费。正确做法，一般是在消费之前先移除该Key，然后进行消息dispatch，如果担心消息处理失败，可以做回滚操作（本次没有做这个处理）。
## 现在有点想上代码了
### Server端
```java
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
        client.read(byteBuffer);
        return byteBuffer;
    }

    /**
     * 转发消息
     *
     * @param client
     * @param byteBuffer
     * @throws IOException
     */
    public void dispatchMsg(SocketChannel client, ByteBuffer byteBuffer) throws IOException {
        //不可被修改
        Set<SelectionKey> keys = selector.keys();
        //这里需要转换状态为写 position = 0，limit=1+message.length
        byteBuffer.flip();
        for (SelectionKey key : keys) {
            SelectableChannel channel = key.channel();
            //判断是不是SocketChannel，转发是针对客户端通道而言的
            if (channel != null && channel instanceof SocketChannel) {
                SocketChannel targetClient = (SocketChannel) channel;
                //如果是本人，需要将第一位改为byte 0
                //写消息 这时候position=0，limit=1+message.length
                if (channel == client) {
                    byteBuffer.put(0, (byte) 0);
                } else {
                    //标志位 1代表本人，0代表其他人 此时 position=1 limit=128
                    byteBuffer.put(0, (byte) 1);
                }
                targetClient.write(byteBuffer);
                //重复使用
                byteBuffer.position(0);
            }
        }
    }

    /**
     * 使用默认端口
     * @return
     */
    public static ChatRoomServer start() {
        return start(DEFAULT_PORT);
    }

    /**
     * 使用自定义端口
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

```
### Client
```java
package com.yzz.nio.chatroom.client;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * describe:
 * E-mail:yzzstyle@163.com  date:2019/1/15
 *
 * @Since 0.0.1
 */
public class ChatRoomClient {

    private Charset charset = Charset.forName("UTF-8");
    private SocketChannel socketChannel;
    private Scanner scanner = new Scanner(System.in);
    private ByteBuffer byteBuffer = ByteBuffer.allocateDirect(128);
    private Logger log = Logger.getLogger(ChatRoomClient.class);
    private ExecutorService pool = Executors.newFixedThreadPool(2);
    private volatile boolean stop;

    private ChatRoomClient(String host, int port) throws IOException {
        SocketAddress socketAddress = new InetSocketAddress(host, port);
        socketChannel = SocketChannel.open(socketAddress);
        log.info("client has connected remote " + host + ":" + port);
    }


    public void write() {
        try {
            while (!stop) {
                String msg = scanner.next();
                ByteBuffer b = charset.encode(msg);
                socketChannel.write(b);
                scanner.reset();
            }
        } catch (IOException e) {
            e.printStackTrace();
            clear();
        }

    }

    public void read() {
        try {
            while (!stop) {
                //阻塞
                socketChannel.read(byteBuffer);
                //写
                byteBuffer.flip();
                byte tag = byteBuffer.get();
                CharBuffer charBuffer = charset.decode(byteBuffer);
                if (tag == 0) {
                    System.out.println(charBuffer + "(本人)");
                } else {
                    System.out.println(charBuffer);
                }


                byteBuffer.clear();
            }
        } catch (IOException e) {
            e.printStackTrace();
            clear();
        }

    }

    /**
     * 释放资源
     */
    private void clear() {
        try {
            stop = true;
            if (null != socketChannel) socketChannel.close();
            if (scanner != null) scanner.close();
            if (byteBuffer != null) byteBuffer.clear();
            if (pool != null && !pool.isShutdown()) pool.shutdownNow();
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    /**
     * 启动，通过两个线程去服务读写
     * @param host
     * @param port
     */
    public static void start(String host, int port) {
        try {
            ChatRoomClient chatRoomClient = new ChatRoomClient(host, port);
            chatRoomClient.pool.execute(() -> {
                chatRoomClient.read();
            });
            chatRoomClient.pool.execute(() -> {
                chatRoomClient.write();
            });
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}

```



## 总结
在写完这个简易的小东西后，更加深入理解了NIO的运行流程，和编码需要注意的一些问题，欢迎大家提问。