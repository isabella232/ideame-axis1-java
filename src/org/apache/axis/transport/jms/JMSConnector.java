/*
 * The Apache Software License, Version 1.1
 *
 *
 * Copyright (c) 2001, 2002 The Apache Software Foundation.  All rights
 * reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * 3. The end-user documentation included with the redistribution,
 *    if any, must include the following acknowledgment:
 *       "This product includes software developed by the
 *        Apache Software Foundation (http://www.apache.org/)."
 *    Alternately, this acknowledgment may appear in the software itself,
 *    if and wherever such third-party acknowledgments normally appear.
 *
 * 4. The names "Axis" and "Apache Software Foundation" must
 *    not be used to endorse or promote products derived from this
 *    software without prior written permission. For written
 *    permission, please contact apache@apache.org.
 *
 * 5. Products derived from this software may not be called "Apache",
 *    nor may "Apache" appear in their name, without prior written
 *    permission of the Apache Software Foundation.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED.  IN NO EVENT SHALL THE APACHE SOFTWARE FOUNDATION OR
 * ITS CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
 * USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 */

package org.apache.axis.transport.jms;

import java.util.Map;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Iterator;

import java.io.ByteArrayOutputStream;

import javax.jms.JMSException;
import javax.jms.JMSSecurityException;
import javax.jms.InvalidDestinationException;
import javax.jms.ExceptionListener;
import javax.jms.ConnectionFactory;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.MessageListener;
import javax.jms.Destination;
import javax.jms.Session;
import javax.jms.Message;
import javax.jms.BytesMessage;

import javax.naming.Context;
import javax.naming.NamingException;

// No vendor dependent exception classes
//import progress.message.client.EUserAlreadyConnected;
//import progress.message.jclient.ErrorCodes;

/**
 * JMSConnector is an abstract class that encapsulates the work of connecting
 *   to JMS destinations. Its subclasses are TopicConnector and QueueConnector
 *   which further specialize connections to the pub-sub and the ptp domains.
 *   It also implements the capability to retry connections in the event of
 *   failures.
 *
 * @author Jaime Meritt  (jmeritt@sonicsoftware.com)
 * @author Richard Chung (rchung@sonicsoftware.com)
 * @author Dave Chappell (chappell@sonicsoftware.com)
 */
public abstract class JMSConnector
{
    protected int             m_numRetries;
    protected long            m_connectRetryInterval;
    protected long            m_interactRetryInterval;
    protected long            m_timeoutTime;
    protected long            m_poolTimeout;
    protected AsyncConnection m_receiveConnection;
    protected SyncConnection  m_sendConnection;
    protected int             m_numSessions;
    protected boolean         m_allowReceive;
    private   Context         m_context;

    public JMSConnector(ConnectionFactory connectionFactory,
                        int numRetries,
                        int numSessions,
                        long connectRetryInterval,
                        long interactRetryInterval,
                        long timeoutTime,
                        boolean allowReceive,
                        String clientID,
                        String username,
                        String password,
                        Context context)
        throws JMSException
    {
        m_numRetries = numRetries;
        m_connectRetryInterval = connectRetryInterval;
        m_interactRetryInterval = interactRetryInterval;
        m_timeoutTime = timeoutTime;
        m_poolTimeout = timeoutTime/(long)numRetries;
        m_numSessions = numSessions;
        m_allowReceive = allowReceive;
        m_context = context;

        // try to connect initially so we can fail fast
        // in the case of irrecoverable errors.
        // If we fail in a recoverable fashion we will retry
        javax.jms.Connection sendConnection = createConnectionWithRetry(
                                                                connectionFactory,
                                                                username,
                                                                password);
        m_sendConnection = createSyncConnection(connectionFactory, sendConnection,
                                                m_numSessions, "SendThread",
                                                clientID,
                                                username,
                                                password);

        m_sendConnection.start();

        if(m_allowReceive)
        {
            javax.jms.Connection receiveConnection = createConnectionWithRetry(
                                                            connectionFactory,
                                                            username,
                                                            password);
            m_receiveConnection = createAsyncConnection(connectionFactory,
                                                        receiveConnection,
                                                        "ReceiveThread",
                                                        clientID,
                                                        username,
                                                        password);
            m_receiveConnection.start();
        }
    }

    protected javax.jms.Connection createConnectionWithRetry(
                                            ConnectionFactory connectionFactory,
                                            String username,
                                            String password)
        throws JMSException
    {
        javax.jms.Connection connection = null;
        for(int numTries = 1; connection == null; numTries++)
        {
            try
            {
                connection = internalConnect(connectionFactory, username, password);
            }
            catch(JMSException jmse)
            {
                if(isIrrecoverable(jmse) || numTries == m_numRetries)
                    throw jmse;
                else
                    try{Thread.sleep(m_connectRetryInterval);}catch(InterruptedException ie){};
            }
        }
        return connection;
    }

    public void stop()
    {
        m_sendConnection.stopConnection();
        if(m_allowReceive)
            m_receiveConnection.stopConnection();
    }

    public void start()
    {
        m_sendConnection.startConnection();
        if(m_allowReceive)
            m_receiveConnection.startConnection();
    }

    public void shutdown()
    {
        m_sendConnection.shutdown();
        if(m_allowReceive)
            m_receiveConnection.shutdown();
    }

    public JMSEndpoint createEndpoint(String destinationName)
        throws JMSException, NamingException
    {
        if(m_context == null)
            return internalCreateEndpoint(destinationName);
        else
        {
            Destination destination = (Destination)m_context.lookup(destinationName);
            return createEndpoint(destination);
        }
    }

    protected abstract JMSEndpoint internalCreateEndpoint(String destinationName)
        throws JMSException;

    public abstract JMSEndpoint createEndpoint(Destination destination)
        throws JMSException;


    /**
     *
     * @todo decide what constitutes an irrecoverable error
     */
    private boolean isIrrecoverable(JMSException jmsException)
    {
        return jmsException instanceof JMSSecurityException;
    }

    protected abstract javax.jms.Connection internalConnect(
                                                ConnectionFactory connectionFactory,
                                                String username,
                                                String password)
        throws JMSException;

    private abstract class Connection extends Thread implements ExceptionListener
    {
        private ConnectionFactory m_connectionFactory;
        protected javax.jms.Connection m_connection;

        protected boolean m_isActive;
        private boolean m_needsToConnect;
        private boolean m_startConnection;
        private String m_clientID;
        private String m_username;
        private String m_password;

        private Object m_jmsLock;
        private Object m_lifecycleLock;


        protected Connection(ConnectionFactory connectionFactory,
                             javax.jms.Connection connection,
                             String threadName,
                             String clientID,
                             String username,
                             String password)
            throws JMSException
        {
            super(threadName);
            m_connectionFactory = connectionFactory;

            m_clientID = clientID;
            m_username = username;
            m_password = password;

            m_jmsLock = new Object();
            m_lifecycleLock = new Object();

            if (connection != null)
            {
                m_needsToConnect = false;
                m_connection = connection;
                m_connection.setExceptionListener(this);
                if(m_clientID != null)
                    m_connection.setClientID(m_clientID);
            }
            else
            {
                m_needsToConnect = true;
            }

            m_isActive = true;
        }

        /**
         * @todo handle non-recoverable errors
         */

        public void run()
        {
            // loop until a connection is made and when a connection is made (re)establish
            // any subscriptions
            while (m_isActive)
            {
                if (m_needsToConnect)
                {
                    m_connection = null;
                    try
                    {
                        m_connection = internalConnect(m_connectionFactory,
                                                       m_username, m_password);
                        m_connection.setExceptionListener(this);
                        if(m_clientID != null)
                            m_connection.setClientID(m_clientID);
                    }
                    catch(JMSException e)
                    {
                        // simply backoff for a while and then retry
                        try { Thread.sleep(m_connectRetryInterval); } catch(InterruptedException ie) { }
                        continue;
                    }
                }
                else
                    m_needsToConnect = true; // When we'll get to the "if (needsToConnect)" statement the next time it will be because
                                           // we lost the connection

                // we now have a valid connection so establish some context
                try
                {
                    internalOnConnect();
                }
                catch(JMSException e)
                {
                    // insert code to handle non recoverable errors
                    // simply retry
                    continue;
                }

                synchronized(m_jmsLock)
                {
                    try { m_jmsLock.wait(); } catch(InterruptedException ie) { } // until notified due to some change in status
                }
            }

            // no longer staying connected, so see what we can cleanup
            internalOnShutdown();
        }



        void startConnection()
        {
            synchronized(m_lifecycleLock)
            {
                if(m_startConnection)
                    return;
                m_startConnection = true;
                try {m_connection.start();}catch(Throwable e) { } // ignore
            }
        }

        void stopConnection()
        {
            synchronized(m_lifecycleLock)
            {
                if(!m_startConnection)
                    return;
                m_startConnection = false;
                try {m_connection.stop();}catch(Throwable e) { } // ignore
            }
        }

        void shutdown()
        {
            m_isActive = false;
            synchronized(m_jmsLock)
            {
                m_jmsLock.notifyAll();
            }
        }



        public void onException(JMSException exception)
        {
            //right now we will blindly reconnect even if the connection hasn't
            //dropped.  This is an area that can be improved with a vendor
            //specific exception mapping like the one below

            //let's see if the connection really dropped
            //this could be another type of problem that
            //doesn't require the reestablishment of context
            //if(!ErrorCodes.testException(exception, ErrorCodes.ERR_CONNECTION_DROPPED))
            //    return;

            onException();
            synchronized(m_jmsLock)
            {
                m_jmsLock.notifyAll();
            }
        }

        private final void internalOnConnect()
            throws JMSException
        {
            onConnect();
            synchronized(m_lifecycleLock)
            {
                if(m_startConnection)
                {
                    try {m_connection.start();}catch(Throwable e) { } // ignore
                }
            }
        }

        private final void internalOnShutdown()
        {
            stopConnection();
            onShutdown();
            try { m_connection.close(); } catch(Throwable e) { } // ignore
        }

        protected abstract void onConnect()throws JMSException;
        protected abstract void onShutdown();
        protected abstract void onException();
    }

    protected abstract SyncConnection createSyncConnection(ConnectionFactory factory,
                                                           javax.jms.Connection connection,
                                                           int numSessions,
                                                           String threadName,
                                                           String clientID,
                                                           String username,
                                                           String password)

        throws JMSException;

    SyncConnection getSendConnection()
    {
        return m_sendConnection;
    }

    protected abstract class SyncConnection extends Connection
    {
        LinkedList m_senders;
        int        m_numSessions;
        Object     m_senderLock;

        SyncConnection(ConnectionFactory connectionFactory,
                       javax.jms.Connection connection,
                       int numSessions,
                       String threadName,
                       String clientID,
                       String username,
                       String password)
            throws JMSException
        {
            super(connectionFactory, connection, threadName,
                  clientID, username, password);
            m_senders = new LinkedList();
            m_numSessions = numSessions;
            m_senderLock = new Object();
        }

        protected abstract SendSession createSendSession(javax.jms.Connection connection)
            throws JMSException;

        protected void onConnect()
          throws JMSException
        {
            synchronized(m_senderLock)
            {
                for(int i = 0; i < m_numSessions; i++)
                {
                    m_senders.add(createSendSession(m_connection));
                }
                m_senderLock.notifyAll();
            }
        }

        byte[] call(JMSEndpoint endpoint, byte[] message, long timeout, HashMap properties)
            throws JMSException
        {
            long timeoutTime = System.currentTimeMillis() + timeout;
            while(true)
            {
                if(System.currentTimeMillis() > timeoutTime)
                {
                    throw new InvokeTimeoutException("Unable to complete call in time allotted");
                }

                SendSession sendSession = null;
                try
                {
                    sendSession = getSessionFromPool(m_poolTimeout);
                    byte[] response =  sendSession.call(endpoint,
                                                        message,
                                                        timeoutTime - System.currentTimeMillis(),
                                                        properties);
                    returnSessionToPool(sendSession);
                    if(response == null)
                    {
                        throw new InvokeTimeoutException("Unable to complete call in time allotted");
                    }
                    return response;
                }
                catch(JMSException jmse)
                {
                    //for now we will assume this is a reconnect related issue
                    //and let the sender be collected
                    if(jmse instanceof JMSSecurityException ||
                       jmse instanceof InvalidDestinationException)
                    {
                        //this we cannot recover from
                        //but it does not invalidate the session
                        returnSessionToPool(sendSession);
                        throw jmse;
                    }

                    //give the reconnect thread a chance to fill the pool
                    Thread.yield();
                    continue;
                }
                catch(NullPointerException npe)
                {
                    Thread.yield();
                    continue;
                }
            }
        }

        /** @todo add in handling for security exceptions
         *  @todo add support for timeouts */
        void send(JMSEndpoint endpoint, byte[] message, HashMap properties)
            throws JMSException
        {
            long timeoutTime = System.currentTimeMillis() + m_timeoutTime;
            while(true)
            {
                if(System.currentTimeMillis() > timeoutTime)
                {
                    throw new InvokeTimeoutException("Cannot complete send in time allotted");
                }

                SendSession sendSession = null;
                try
                {
                    sendSession = getSessionFromPool(m_poolTimeout);
                    sendSession.send(endpoint, message, properties);
                    returnSessionToPool(sendSession);
                }
                catch(JMSException jmse)
                {
                    //for now we will assume this is a reconnect related issue
                    //and let the sender be collected
                    if(jmse instanceof JMSSecurityException ||
                       jmse instanceof InvalidDestinationException)
                    {
                        //this we cannot recover from
                        //but it does not invalidate the session
                        returnSessionToPool(sendSession);
                        throw jmse;
                    }
                    //give the reconnect thread a chance to fill the pool
                    Thread.yield();
                    continue;
                }
                catch(NullPointerException npe)
                {
                    //give the reconnect thread a chance to fill the pool
                    Thread.yield();
                    continue;
                }
                break;
            }
        }

        protected void onException()
        {
            synchronized(m_senderLock)
            {
                m_senders.clear();
            }
        }

        protected void onShutdown()
        {
            synchronized(m_senderLock)
            {
                Iterator senders = m_senders.iterator();
                while(senders.hasNext())
                {
                    SendSession session = (SendSession)senders.next();
                    session.cleanup();
                }
                m_senders.clear();
            }
        }

        private SendSession getSessionFromPool(long timeout)
        {
            synchronized(m_senderLock)
            {
                while(m_senders.size() == 0)
                {
                    try
                    {
                        m_senderLock.wait(timeout);
                        if(m_senders.size() == 0)
                        {
                            return null;
                        }
                    }
                    catch(InterruptedException ignore)
                    {
                        return null;
                    }
                }
                return (SendSession)m_senders.removeFirst();
            }
        }

        private void returnSessionToPool(SendSession sendSession)
        {
            synchronized(m_senderLock)
            {
                m_senders.addLast(sendSession);
                m_senderLock.notifyAll();
            }
        }

        protected abstract class SendSession extends ConnectorSession
        {
            MessageProducer m_producer;

            SendSession(Session session,
                        MessageProducer producer)
              throws JMSException
            {
                super(session);
                m_producer = producer;
            }

            protected abstract Destination createTemporaryDestination()
                throws JMSException;

            protected abstract void deleteTemporaryDestination(Destination destination)
                throws JMSException;

            protected abstract MessageConsumer createConsumer(Destination destination)
                throws JMSException;

            protected abstract void send(Destination destination,
                                         Message message,
                                         int deliveryMode,
                                         int priority,
                                         long timeToLive)
                throws JMSException;

            void send(JMSEndpoint endpoint, byte[] message, HashMap properties)
                throws JMSException
            {
                BytesMessage jmsMessage = m_session.createBytesMessage();
                jmsMessage.writeBytes(message);
                int deliveryMode = extractDeliveryMode(properties);
                int priority = extractPriority(properties);
                long timeToLive = extractTimeToLive(properties);

                if(properties != null && !properties.isEmpty())
                    setProperties(properties, jmsMessage);

                send(endpoint.getDestination(m_session), jmsMessage, deliveryMode,
                     priority, timeToLive);
            }


            void cleanup()
            {
                try{m_producer.close();}catch(Throwable t){}
                try{m_session.close();}catch(Throwable t){}
            }

            byte[] call(JMSEndpoint endpoint, byte[] message, long timeout,
                        HashMap properties)
                throws JMSException
            {
                Destination reply = createTemporaryDestination();
                MessageConsumer subscriber = createConsumer(reply);
                BytesMessage jmsMessage = m_session.createBytesMessage();
                jmsMessage.writeBytes(message);
                jmsMessage.setJMSReplyTo(reply);

                int deliveryMode = extractDeliveryMode(properties);
                int priority = extractPriority(properties);
                long timeToLive = extractTimeToLive(properties);

                if(properties != null && !properties.isEmpty())
                    setProperties(properties, jmsMessage);

                send(endpoint.getDestination(m_session), jmsMessage, deliveryMode,
                     priority, timeToLive);
                BytesMessage response = null;
                try {
                    response = (BytesMessage)subscriber.receive(timeout);
                } catch (ClassCastException cce) {
                    throw new InvokeException
                            ("Error: unexpected message type received - expected BytesMessage");
                }
                byte[] respBytes = null;
                if(response != null)
                {
                    byte[] buffer = new byte[8 * 1024];
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    for(int bytesRead = response.readBytes(buffer);
                        bytesRead != -1; bytesRead = response.readBytes(buffer))
                    {
                        out.write(buffer, 0, bytesRead);
                    }
                    respBytes = out.toByteArray();
                }
                subscriber.close();
                deleteTemporaryDestination(reply);
                return respBytes;
            }

            private int extractPriority(HashMap properties)
            {
                return MapUtils.removeIntProperty(properties, JMSConstants.PRIORITY,
                                         JMSConstants.DEFAULT_PRIORITY);
            }

            private int extractDeliveryMode(HashMap properties)
            {
                return MapUtils.removeIntProperty(properties, JMSConstants.DELIVERY_MODE,
                                         JMSConstants.DEFAULT_DELIVERY_MODE);
            }

            private long extractTimeToLive(HashMap properties)
            {
                return MapUtils.removeLongProperty(properties, JMSConstants.TIME_TO_LIVE,
                                          JMSConstants.DEFAULT_TIME_TO_LIVE);
            }

            private void setProperties(HashMap properties, Message message)
                throws JMSException
            {
                Iterator propertyIter = properties.entrySet().iterator();
                while(propertyIter.hasNext())
                {
                    Map.Entry property = (Map.Entry)propertyIter.next();
                    setProperty((String)property.getKey(), property.getValue(),
                                message);
                }
            }

            private void setProperty(String property, Object value, Message message)
                throws JMSException
            {
                if(property == null)
                    return;
                if(property.equals(JMSConstants.JMS_CORRELATION_ID))
                    message.setJMSCorrelationID((String)value);
                else if(property.equals(JMSConstants.JMS_CORRELATION_ID_AS_BYTES))
                    message.setJMSCorrelationIDAsBytes((byte[])value);
                else if(property.equals(JMSConstants.JMS_TYPE))
                    message.setJMSType((String)value);
                else
                    message.setObjectProperty(property, value);
            }
        }
    }

    AsyncConnection getReceiveConnection()
    {
        return m_receiveConnection;
    }

    protected abstract AsyncConnection createAsyncConnection(ConnectionFactory factory,
                                                             javax.jms.Connection connection,
                                                             String threadName,
                                                             String clientID,
                                                             String username,
                                                             String password)

        throws JMSException;

    protected abstract class AsyncConnection extends Connection
    {
        HashMap m_subscriptions;
        Object m_subscriptionLock;

        protected AsyncConnection(ConnectionFactory connectionFactory,
                                  javax.jms.Connection connection,
                                  String threadName,
                                  String clientID,
                                  String username,
                                  String password)
            throws JMSException
        {
            super(connectionFactory, connection, threadName,
                  clientID, username, password);
            m_subscriptions = new HashMap();
            m_subscriptionLock = new Object();
        }

        protected abstract ListenerSession createListenerSession(
                                                javax.jms.Connection connection,
                                                Subscription subscription)
            throws JMSException;

        protected void onShutdown()
        {
            synchronized(m_subscriptionLock)
            {
                Iterator subscriptions = m_subscriptions.keySet().iterator();
                while(subscriptions.hasNext())
                {
                    Subscription subscription = (Subscription)subscriptions.next();
                    ListenerSession session  = (ListenerSession)
                                                m_subscriptions.get(subscription);
                    if(session != null)
                    {
                        session.cleanup();
                    }

                }
                m_subscriptions.clear();
            }
        }

        /**
         * @todo add in security exception propagation
         * @param subscription
         */
        void subscribe(Subscription subscription)
            throws JMSException
        {
            long timeoutTime = System.currentTimeMillis() + m_timeoutTime;
            synchronized(m_subscriptionLock)
            {
                if(m_subscriptions.containsKey(subscription))
                    return;
                while(true)
                {
                    if(System.currentTimeMillis() > timeoutTime)
                    {
                        throw new InvokeTimeoutException("Cannot subscribe listener");
                    }

                    try
                    {
                        ListenerSession session = createListenerSession(m_connection,
                                                                        subscription);
                        m_subscriptions.put(subscription, session);
                        break;
                    }
                    catch(JMSException jmse)
                    {
                        //we MAY be reconnecting or something else is screwy
                        if(jmse instanceof JMSSecurityException ||
                           jmse instanceof InvalidDestinationException)
                        {
                            throw jmse;
                        }
                        /* If we had a vendor specific exception mapping this
                        would help when doing stuff like this
                        String message = jmse.getMessage();
                        if(message != null && message.startsWith("Queue not found"))
                        {
                            throw jmse;
                        }
                        Exception linkedException = jmse.getLinkedException();
                        if(linkedException != null &&
                           linkedException instanceof EUserAlreadyConnected)
                        {
                            throw jmse;
                        }*/
                        try{m_subscriptionLock.wait(m_interactRetryInterval);}
                        catch(InterruptedException ignore){}
                        //give reconnect a chance
                        Thread.yield();
                        continue;
                    }
                    catch(NullPointerException jmse)
                    {
                        //we ARE reconnecting
                        try{m_subscriptionLock.wait(m_interactRetryInterval);}
                        catch(InterruptedException ignore){}
                        //give reconnect a chance
                        Thread.yield();
                        continue;
                    }
                }
            }
        }

        void unsubscribe(Subscription subscription)
        {
            long timeoutTime = System.currentTimeMillis() + m_timeoutTime;
            synchronized(m_subscriptionLock)
            {
                if(!m_subscriptions.containsKey(subscription))
                    return;
                while(true)
                {
                    if(System.currentTimeMillis() > timeoutTime)
                    {
                        throw new InvokeTimeoutException("Cannot unsubscribe listener");
                    }

                    //give reconnect a chance
                    Thread.yield();
                    try
                    {
                        ListenerSession session = (ListenerSession)
                                                m_subscriptions.get(subscription);
                        session.cleanup();
                        m_subscriptions.remove(subscription);
                        break;
                    }
                    catch(NullPointerException jmse)
                    {
                        //we are reconnecting
                        try{m_subscriptionLock.wait(m_interactRetryInterval);}
                        catch(InterruptedException ignore){}
                        continue;
                    }
                }
            }
        }

        protected void onConnect()
            throws JMSException
        {
            synchronized(m_subscriptionLock)
            {
                Iterator subscriptions = m_subscriptions.keySet().iterator();
                while(subscriptions.hasNext())
                {
                    Subscription subscription = (Subscription)subscriptions.next();

                    if(m_subscriptions.get(subscription) == null)
                    {
                        m_subscriptions.put(subscription,
                            createListenerSession(m_connection, subscription));
                    }
                }
                m_subscriptionLock.notifyAll();
            }
        }

        protected void onException()
        {
            synchronized(m_subscriptionLock)
            {
                Iterator subscriptions = m_subscriptions.keySet().iterator();
                while(subscriptions.hasNext())
                {
                    Subscription subscription = (Subscription)subscriptions.next();
                    m_subscriptions.put(subscription, null);
                }
            }
        }



        protected class ListenerSession extends ConnectorSession
        {
            protected MessageConsumer m_consumer;
            protected Subscription    m_subscription;

            ListenerSession(Session session,
                            MessageConsumer consumer,
                            Subscription subscription)
                throws JMSException
            {
                super(session);
                m_subscription = subscription;
                m_consumer = consumer;
                Destination destination = subscription.m_endpoint.getDestination(m_session);
                m_consumer.setMessageListener(subscription.m_listener);
            }

            void cleanup()
            {
                try{m_consumer.close();}catch(Exception ignore){}
                try{m_session.close();}catch(Exception ignore){}
            }

        }
    }



    private abstract class ConnectorSession
    {
        Session m_session;

        ConnectorSession(Session session)
          throws JMSException
        {
            m_session = session;
        }

    }

}