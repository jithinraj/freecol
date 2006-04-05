
package net.sf.freecol.common.networking;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.Socket;
import java.util.logging.Logger;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import net.sf.freecol.common.FreeColException;

import org.w3c.dom.Element;





/**
* A network connection. Responsible for both sending and receiving
* network messages.
*
* @see #send
* @see #sendAndWait
* @see #ask
*/
public class Connection {
    private static final Logger logger = Logger.getLogger(Connection.class.getName());

    public static final String  COPYRIGHT = "Copyright (C) 2003-2005 The FreeCol Team";
    public static final String  LICENSE = "http://www.gnu.org/licenses/gpl.html";
    public static final String  REVISION = "$Revision$";

    private final OutputStream out;
    private final InputStream in;
    private final Socket socket;
    private final Transformer xmlTransformer;
    private final ReceivingThread thread;
    private MessageHandler messageHandler;

    /**
    * Dead constructor, for DummyConnection purposes.
    */
    protected Connection() {
        out = null;
        in = null;
        socket = null;
        thread = null;
        xmlTransformer = null;
    }

    /**
    * Sets up a new socket with specified host and port and uses {@link #Connection(Socket, MessageHandler)}.
    *
    * @param host The host to connect to.
    * @param port The port to connect to.
    * @param messageHandler The MessageHandler to call for each message received.
    * @throws IOException
    */
    public Connection(String host, int port, MessageHandler messageHandler) throws IOException {
        this(new Socket(host, port), messageHandler);
    }


    /**
    * Creates a new <code>Connection</code> with the specified <code>Socket</code>
    * and {@link MessageHandler}.
    *
    * @param socket The socket to the client.
    * @param messageHandler The MessageHandler to call for each message received.
    * @throws IOException
    */
    public Connection(Socket socket, MessageHandler messageHandler) throws IOException {
        this.messageHandler = messageHandler;
        this.socket = socket;

        out = socket.getOutputStream();
        in = socket.getInputStream();

        Transformer myTransformer;
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            myTransformer = factory.newTransformer();
            myTransformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        } catch (TransformerException e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            logger.warning(sw.toString());

            myTransformer = null;
        }
        xmlTransformer = myTransformer;

        thread = new ReceivingThread(this, in);
        thread.start();
    }



    /**
    * Sends a "disconnect"-message and closes this connection.
    *
    * @throws IOException
    */
    public void close() throws IOException {
        Element disconnectElement = Message.createNewRootElement("disconnect");
        send(disconnectElement);

        reallyClose();
    }


    /**
    * Closes this connection.
    *
    * @throws IOException
    */
    public void reallyClose() throws IOException {
        if (thread != null) {
            thread.askToStop();
        }
            
        if (out != null) {
            out.close();
        }

        if (socket != null) {
            socket.close();
        }

        if (in != null) {
            in.close();
        }

        logger.info("Connection closed.");
    }


    /**
    * Sends the given message over this Connection.
    *
    * @param element The element (root element in a DOM-parsed XML tree) that
    *                holds all the information
    * @throws IOException If an error occur while sending the message.
    * @see #sendAndWait
    * @see #ask
    */
    public void send(Element element) throws IOException {
        synchronized (out) {
            try {
                xmlTransformer.transform(new DOMSource(element), new StreamResult(out));
            } catch (TransformerException e) {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                logger.warning(sw.toString());
            }

            out.write('\n');
            out.flush();
        }
    }


    /**
    * On IBM's JDK version 1.4.2 the Element.toString method doesn't give you
    * a normal XML string, which is fine because the Sun API docs never said that
    * it should. On Sun's JDK version 1.5 this is also the case. So now instead
    * of calling Element.toString you should use this method.
    *
    * @param element The Element to convert.
    * @return The string representation of the given Element without the xml version tag.
    */
    String convertElementToString(Element element) {
        synchronized (out) { // Also a lock for: xmlTransformer
            String xml;
            try {
                StringWriter stringWriter = new StringWriter();
                xmlTransformer.transform(new DOMSource(element), new StreamResult(stringWriter));
                xml = stringWriter.toString();
            } catch (TransformerException e) {
                xml = e.getMessage();
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                logger.warning(sw.toString());
            }
            return xml;
        }
    }


    /**
    * Sends a message to the other peer and returns the reply.
    *
    * @param element The question for the other peer.
    * @return The reply from the other peer.
    * @throws IOException If an error occur while sending the message.
    * @see #send
    * @see #sendAndWait
    */
    public Element ask(Element element) throws IOException {
        int networkReplyId = thread.getNextNetworkReplyId();

        Element questionElement = element.getOwnerDocument().createElement("question");
        questionElement.setAttribute("networkReplyId", Integer.toString(networkReplyId));
        questionElement.appendChild(element);

        if (Thread.currentThread() == thread) {
            logger.warning("Attempt to 'wait()' the ReceivingThread.");
            throw new IOException("Attempt to 'wait()' the ReceivingThread.");
        } else {
            NetworkReplyObject nro = thread.waitForNetworkReply(networkReplyId);
            send(questionElement);
            return (Element) ((Message) nro.getResponse()).getDocument().getDocumentElement().getFirstChild();
        }
    }


    /**
    * Sends the given message over this <code>Connection</code>
    * and waits for confirmation of receiveval before returning.
    *
    * @param element The element (root element in a DOM-parsed XML tree) that
    *                holds all the information
    * @throws IOException If an error occur while sending the message.
    * @see #send
    * @see #ask
    */
    public void sendAndWait(Element element) throws IOException {
        Element reply = ask(element);

        if (reply != null) {
            handleAndSendReply(reply);
        }
    }


    /**
    * Sets the MessageHandler for this Connection.
    * @param mh The new MessageHandler for this Connection.
    */
    public void setMessageHandler(MessageHandler mh) {
        messageHandler = mh;
    }

    /**
    * Gets the MessageHandler for this Connection.
    * @return The MessageHandler for this Connection.
    */
    public MessageHandler getMessageHandler() {
        return messageHandler;
    }

    public void handleAndSendReply(Message message) {
        handleAndSendReply(message.getDocument().getDocumentElement());
    }


    /**
    * Handles a message using the registered <code>MessageHandler</code>.
    * @param element The message as a DOM-parsed XML-tree.
    */
    public void handleAndSendReply(Element element) {
        try {
            if (element.getTagName().equals("question")) {
                String networkReplyId = element.getAttribute("networkReplyId");

                Element reply = messageHandler.handle(this, (Element) element.getFirstChild());

                if (reply == null) {
                    reply = Message.createNewRootElement("reply");
                    reply.setAttribute("networkReplyId", networkReplyId);
                    logger.info("reply == null");
                } else {
                    Element replyHeader = reply.getOwnerDocument().createElement("reply");
                    replyHeader.setAttribute("networkReplyId", networkReplyId);
                    replyHeader.appendChild(reply);
                    reply = replyHeader;
                }

                send(reply);
            } else {
                Element reply = messageHandler.handle(this, element);

                if (reply != null) {
                    send(reply);
                }
            }
        } catch (FreeColException e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            logger.warning(sw.toString());
        } catch (IOException e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            logger.warning(sw.toString());
        }
    }


    /**
    * Gets the socket.
    * @return The <code>Socket</code> used while communicating with the other peer.
    */
    public Socket getSocket() {
        return socket;
    }
}
