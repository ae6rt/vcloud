/*
 * Copyright (c) 2010 by J. Brisbin <jon@jbrisbin.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.jbrisbin.vcloud.cache;

import com.rabbitmq.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author J. Brisbin <jon@jbrisbin.com>
 */
public class RabbitMQAsyncCache implements AsyncCache {

  protected final Logger log = LoggerFactory.getLogger( getClass() );
  protected final boolean debug = log.isDebugEnabled();

  protected String id;
  @Autowired
  protected ConnectionFactory connectionFactory;
  protected ExecutorService workerPool = Executors.newCachedThreadPool();
  protected Connection connection = null;
  /**
   * Name of the topic exchange to use for requesting objects.
   */
  protected String objectRequestExchange = "amq.topic";

  /**
   * Name of the fanout exchange used for sending heartbeat messages.
   */
  protected String heartbeatExchange = "amq.fanout";
  /**
   * How often to send out the heartbeat.
   */
  protected long heartbeatInterval = 3000L;
  /**
   * Is this cache active?
   */
  protected AtomicBoolean active = new AtomicBoolean( false );
  /**
   * Tasks that are currently running.
   */
  protected List<Future<?>> activeTasks = new ArrayList<Future<?>>();
  /**
   * Set of cache IDs for our cacheNodes. Used to determine if all responses have returned or
   * not.
   */
  protected ConcurrentSkipListSet<String> cacheNodes = new ConcurrentSkipListSet<String>();
  /**
   * Number of cacheNodes we expect responses from.
   */
  protected AtomicInteger numOfCacheNodes = new AtomicInteger( 1 );
  /**
   * Timer for issuing delayed tasks.
   */
  protected Timer delayTimer = new Timer( true );
  /**
   * How many Loaders to run concurrently.
   */
  protected int maxWorkers = 3;
  /**
   * How long to wait for exepcted responses.
   */
  protected long loadTimeout = 3000L;
  /**
   * Event handlers waiting on objects to be loaded.
   */
  protected ConcurrentSkipListMap<String, List<AsyncCacheCallback>> objectLoadCallbacks = new ConcurrentSkipListMap<String, List<AsyncCacheCallback>>();
  protected BlockingQueue<ObjectMessage> objectMessages = new LinkedBlockingQueue<ObjectMessage>();
  protected BlockingQueue<CommandMessage> commandMessages = new LinkedBlockingQueue<CommandMessage>();

  public RabbitMQAsyncCache() {
  }

  @Override
  public void setId( String id ) {
    this.id = id;
  }

  @Override
  public String getId() {
    return this.id;
  }

  public ConnectionFactory getConnectionFactory() {
    return connectionFactory;
  }

  public void setConnectionFactory( ConnectionFactory connectionFactory ) {
    this.connectionFactory = connectionFactory;
  }

  public String getObjectRequestExchange() {
    return objectRequestExchange;
  }

  public void setObjectRequestExchange( String objectRequestExchange ) {
    this.objectRequestExchange = objectRequestExchange;
  }

  public String getHeartbeatExchange() {
    return heartbeatExchange;
  }

  public void setHeartbeatExchange( String heartbeatExchange ) {
    this.heartbeatExchange = heartbeatExchange;
  }

  public long getHeartbeatInterval() {
    return heartbeatInterval;
  }

  public void setHeartbeatInterval( long heartbeatInterval ) {
    this.heartbeatInterval = heartbeatInterval;
  }

  public int getMaxWorkers() {
    return maxWorkers;
  }

  public void setMaxWorkers( int maxWorkers ) {
    this.maxWorkers = maxWorkers;
    workerPool = Executors.newFixedThreadPool( maxWorkers * 2 );
  }

  public long getLoadTimeout() {
    return loadTimeout;
  }

  public void setLoadTimeout( long loadTimeout ) {
    this.loadTimeout = loadTimeout;
  }

  @Override
  public void add( String id, Object obj ) {
    add( id, obj, Long.MAX_VALUE );
  }

  @Override
  public void add( String id, Object obj, long expiry ) {
    try {
      objectMessages.add( new ObjectMessage( id, objectRequestExchange, id, obj ) );
    } catch ( IOException e ) {
      log.error( e.getMessage(), e );
    }
  }

  @Override
  public void setParent( String childId, String parentId ) {
    throw new IllegalAccessError( "This method is not yet implemented" );
  }

  @Override
  public void remove( String id ) {
    commandMessages.add( new CommandMessage( "clear", objectRequestExchange, id ) );
  }

  @Override
  public void remove( String id, long delay ) {
    // TODO: implement delay in removing object
    remove( id );
  }

  @Override
  public void load( String id, final AsyncCacheCallback callback ) {
    if ( objectLoadCallbacks.containsKey( id ) ) {
      objectLoadCallbacks.get( id ).add( callback );
    } else {
      List<AsyncCacheCallback> callbacks = new ArrayList<AsyncCacheCallback>();
      callbacks.add( callback );
      objectLoadCallbacks.put( id, callbacks );
    }
    commandMessages.add( new CommandMessage( "load", objectRequestExchange, id ) );
  }

  @Override
  public void clear() {
    commandMessages.add( new CommandMessage( "clear", objectRequestExchange, "#" ) );
  }

  @Override
  public void start() {
    active.set( true );

    try {
      Channel channel = getConnection().createChannel();
      channel.exchangeDeclare( objectRequestExchange, "topic", true, false, null );
      channel.queueDeclare( id, true, false, true, null );
    } catch ( IOException e ) {
      log.error( e.getMessage(), e );
    }

    // For loading objects
    for ( int i = 0; i < maxWorkers; i++ ) {
      activeTasks.add( workerPool.submit( new ObjectSender() ) );
      activeTasks.add( workerPool.submit( new CommandSender() ) );
      workerPool.submit( new Runnable() {
        @Override
        public void run() {
          try {
            Channel channel = getConnection().createChannel();
            ObjectLoadMonitor loadMonitor = new ObjectLoadMonitor( channel );
            channel.basicConsume( id, loadMonitor );
          } catch ( IOException e ) {
            log.error( e.getMessage(), e );
          }
        }
      } );
    }

    activeTasks.add( workerPool.submit( new HeartbeatMonitor() ) );
    commandMessages.add( new CommandMessage( "ping", heartbeatExchange, "" ) );
    delayTimer.scheduleAtFixedRate( new TimerTask() {
      @Override
      public void run() {
        if ( cacheNodes.size() > 0 ) {
          numOfCacheNodes.set( cacheNodes.size() );
        }
      }
    }, 0, heartbeatInterval );
  }

  @Override
  public void stop() {
    stop( true );
  }

  @Override
  public void stop( boolean interruptIfRunning ) {
    active.set( false );
    for ( Future<?> f : activeTasks ) {
      f.cancel( interruptIfRunning );
    }
    if ( interruptIfRunning ) {
      workerPool.shutdownNow();
    } else {
      workerPool.shutdown();
    }
  }

  @Override
  public boolean isActive() {
    return active.get();
  }

  @Override
  public void setActive( boolean active ) {
    this.active.set( active );
  }

  protected Connection getConnection() throws IOException {
    if ( null == connection ) {
      connection = connectionFactory.newConnection();
    }
    return connection;
  }

  class HeartbeatMonitor implements Runnable {
    @Override
    public void run() {
      Channel heartbeatChannel = null;
      try {
        heartbeatChannel = getConnection().createChannel();
        QueueingConsumer heartbeatConsumer = new QueueingConsumer( heartbeatChannel );
        heartbeatChannel.basicConsume( id, false, heartbeatConsumer );

        while ( true ) {
          QueueingConsumer.Delivery delivery = heartbeatConsumer.nextDelivery();
          AMQP.BasicProperties properties = delivery.getProperties();
          String type = properties.getType();
          if ( "ping".equals( type ) ) {
            // We don't respond to PING requests since we're a client
          } else if ( "pong".equals( type ) ) {
            byte[] body = delivery.getBody();
            if ( body.length > 0 ) {
              String cacheId = new String( delivery.getBody() );
              if ( null != cacheId && cacheId instanceof String ) {
                cacheNodes.add( cacheId );
              }
            }
          }
        }
      } catch ( InterruptedException e ) {
        // IGNORED
      } catch ( IOException e ) {
        log.error( e.getMessage(), e );
      } finally {
        try {
          heartbeatChannel.close();
        } catch ( Throwable t ) {
        }
      }
    }
  }

  class ObjectSender implements Runnable {

    Channel objectSendChannel = null;
    AMQP.BasicProperties properties = new AMQP.BasicProperties();

    ObjectSender() {
      properties.setType( "store" );
      properties.setReplyTo( id );
    }

    @Override
    public void run() {
      while ( true ) {
        try {
          ObjectMessage msg = objectMessages.take();
          if ( null == objectSendChannel ) {
            objectSendChannel = getConnection().createChannel();
          }
          properties.setCorrelationId( msg.getId() );
          objectSendChannel
              .basicPublish( objectRequestExchange, msg.getRoutingKey(), properties,
                  msg.getBody() );
        } catch ( IOException e ) {
          log.error( e.getMessage(), e );
        } catch ( InterruptedException e ) {
        }
      }
    }
  }

  class CommandSender implements Runnable {

    Channel commandSendChannel = null;
    AMQP.BasicProperties properties = new AMQP.BasicProperties();

    CommandSender() {
    }

    @Override
    public void run() {
      while ( true ) {
        try {
          CommandMessage msg = commandMessages.take();
          if ( null == commandSendChannel ) {
            commandSendChannel = getConnection().createChannel();
          }
          properties.setType( msg.getType() );
          properties.setReplyTo( id );
          commandSendChannel.basicPublish( msg.getExchange(), msg.getRoutingKey(), properties,
              msg.getBody() );
        } catch ( IOException e ) {
          log.error( e.getMessage(), e );
        } catch ( InterruptedException e ) {
        }
      }
    }
  }

  class ObjectLoadMonitor extends DefaultConsumer {

    Channel loadChannel = null;

    ObjectLoadMonitor( Channel loadChannel ) {
      super( loadChannel );
    }

    @Override
    public void handleDelivery( String consumerTag, Envelope envelope,
                                AMQP.BasicProperties properties,
                                byte[] body ) throws IOException {
      String type = properties.getType();
      String objectId = properties.getCorrelationId();
      if ( "response".equals( type ) ) {
        Object obj = null;
        if ( body.length > 0 ) {
          try {
            obj = ObjectMessage.deserialize( body );
          } catch ( ClassNotFoundException e ) {
            log.error( e.getMessage(), e );
            obj = e;
          } catch ( IOException e ) {
            log.error( e.getMessage(), e );
            obj = e;
          }
          List<AsyncCacheCallback> callbacks = objectLoadCallbacks.get( objectId );
          synchronized (callbacks) {
            if ( null != callbacks ) {
              for ( AsyncCacheCallback callback : callbacks ) {
                if ( obj instanceof Throwable ) {
                  callback.onError( (Throwable) obj );
                } else {
                  callback.onObjectLoad( obj );
                }
              }
              callbacks.clear();
            }
          }
        } else {
          // TODO: Handle null messages
        }
      } else {
        log.warn( "Invalid message type: '" + type + "': " + properties );
      }
    }
  }

  class NullObject {
    // To represent <NULL>
  }

}
