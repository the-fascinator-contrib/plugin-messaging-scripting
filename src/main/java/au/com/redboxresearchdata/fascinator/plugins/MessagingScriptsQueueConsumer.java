/*******************************************************************************
 * Copyright (C) 2013 Queensland Cyber Infrastructure Foundation (http://www.qcif.edu.au/)
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 ******************************************************************************/
package au.com.redboxresearchdata.fascinator.plugins;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.googlecode.fascinator.api.PluginException;
import com.googlecode.fascinator.api.PluginManager;
import com.googlecode.fascinator.api.harvester.Harvester;
import com.googlecode.fascinator.api.harvester.HarvesterException;
import com.googlecode.fascinator.api.indexer.Indexer;
import com.googlecode.fascinator.api.storage.Storage;
import com.googlecode.fascinator.api.storage.StorageException;
import com.googlecode.fascinator.api.transformer.TransformerException;
import com.googlecode.fascinator.common.JsonObject;
import com.googlecode.fascinator.common.JsonSimple;
import com.googlecode.fascinator.common.JsonSimpleConfig;
import com.googlecode.fascinator.common.messaging.GenericListener;
import com.googlecode.fascinator.common.messaging.MessagingException;
import com.googlecode.fascinator.common.messaging.MessagingServices;

import javax.script.*;
import au.com.redboxresearchdata.fascinator.messaging.scripting.*;


/**
 * This class enables creation of arbitrary message queues and message consumption through scripting. 
 * 
 * @author <a target='_' href='https://github.com/shilob'>Shilo Banihit</a>
 * 
 */
public class MessagingScriptsQueueConsumer implements GenericListener {
	
	/** Service Loader will look for this */
    public static final String LISTENER_ID = "messagingScripts";
    /** JMS connection */
    private Connection connection;
    
    private Thread thread;
    
    /** Script bindings **/
    private SimpleBindings bindings;
    /** Logging */
    private static Logger log = LoggerFactory.getLogger(MessagingScriptsQueueConsumer.class);
    
    String name;
    /** Key:queue name Value:script path */
    HashMap<String, MessagingScript> scriptMap;
    
    // ReDBox main components..
    /** JSON configuration */
    private JsonSimpleConfig globalConfig;
    /** Indexer object */
    private Indexer indexer;
    /** Storage */
    private Storage storage;
    /** Global configuration */
    private JsonSimpleConfig config;
    /** Messaging services */
    private MessagingServices messaging;

    public MessagingScriptsQueueConsumer() {
    	thread = new Thread(this, LISTENER_ID);    	 
    }
    /**
     * Return the ID string for this listener
     * 
     */
    public String getId() {
        return LISTENER_ID;
    }
    /**
     * 
     */
    public void init(JsonSimpleConfig config) throws Exception {
		this.config = config;
		name = config.getString(null, "config", "name");
		thread.setName(name);
		scriptMap = new HashMap<String, MessagingScript>();
        // load all the scripts configuration
		List<JsonSimple> scriptConfigList = config.getJsonSimpleList("config", "destinations");
		if (scriptConfigList != null) {
			for (JsonSimple scriptConfigJson : scriptConfigList) {
				MessagingScript messagingScript = new MessagingScript();
				String scriptPath = scriptConfigJson.getString(null, "scriptPath");
				messagingScript.destName = scriptConfigJson.getString(null, "name");
				messagingScript.scriptEngineName = scriptConfigJson.getString(null, "scriptEngine");
				messagingScript.destType = scriptConfigJson.getString(null, "type");
				messagingScript.config = scriptConfigJson;
				log.debug("Queue Name: " + messagingScript.destName + ", Type: " + messagingScript.destType + ", scriptPath:" + scriptPath);
				if (scriptPath != null) {
					messagingScript.file = new File(scriptPath);
					if (messagingScript.file.exists()) {
						scriptMap.put(messagingScript.destName, messagingScript);
					} else {
						log.error("Script File not found: {}", messagingScript.file.getAbsolutePath());
					}
				} else {
					log.error("Script Path is null");
				}
			}
		} else {
			log.info("No messaging scripts configured.");
		}
		try {
            messaging = MessagingServices.getInstance();
        } catch (MessagingException ex) {
            log.error("Failed to start connection: {}", ex.getMessage());
            throw ex;
        }
        File sysFile = null;
        try {
            globalConfig = new JsonSimpleConfig();
            sysFile = JsonSimpleConfig.getSystemFile();

            // Load the indexer plugin
            String indexerId = globalConfig.getString(
                    "solr", "indexer", "type");
            if (indexerId == null) {
                throw new Exception("No Indexer ID provided");
            }
            indexer = PluginManager.getIndexer(indexerId);
            if (indexer == null) {
                throw new Exception("Unable to load Indexer '"+indexerId+"'");
            }
            indexer.init(sysFile);

            // Load the storage plugin
            String storageId = globalConfig.getString(
                    "file-system", "storage", "type");
            if (storageId == null) {
                throw new Exception("No Storage ID provided");
            }
            storage = PluginManager.getStorage(storageId);
            if (storage == null) {
                throw new Exception("Unable to load Storage '"+storageId+"'");
            }
            storage.init(sysFile);
            // Prepare bindings
            bindings = new SimpleBindings();
            bindings.put("indexer", indexer);
            bindings.put("storage", storage);
            bindings.put("messaging", messaging);
            bindings.put("globalConfig", globalConfig);
            bindings.put("log", log);
            
        } catch (IOException ioe) {
            log.error("Failed to read configuration: {}", ioe.getMessage());
            throw ioe;
        } catch (PluginException pe) {
            log.error("Failed to initialise plugin: {}", pe.getMessage());
            throw pe;
        }

        
        log.debug("JsonHarvester initialised.");
	}
    
    public void run() {
        try {            
            // Get a connection to the broker
            String brokerUrl = globalConfig.getString(
                    ActiveMQConnectionFactory.DEFAULT_BROKER_BIND_URL,
                    "messaging", "url");
            ActiveMQConnectionFactory connectionFactory =
                    new ActiveMQConnectionFactory(brokerUrl);
            connection = connectionFactory.createConnection();
            
            for (String destName : scriptMap.keySet()) {
            	MessagingScript messagingScript = scriptMap.get(destName);
            	messagingScript.session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            	messagingScript.consumer = messagingScript.session.createConsumer(messagingScript.destType.equals("queue") ? messagingScript.session.createQueue(destName) : messagingScript.session.createTopic(destName));
            	messagingScript.consumer.setMessageListener(messagingScript);
            	messagingScript.bindings = bindings;
            }                                  
            connection.start();
            log.info("'{}' is running...", name);            
        } catch (JMSException ex) {
            log.error("Error starting message thread!", ex);
        }
    }
    
    /**
     * Start the queue based on the name identifier
     * 
     * @throws JMSException if an error occurred starting the JMS connections
     */
    public void start() throws Exception {
    	log.info("Starting {}...", name);
        thread.start();
    }

    /**
     * Stop the JSON Harvest Queue Consumer. Including stopping the storage and
     * indexer
     */
    public void stop() throws Exception {
        log.info("Stopping {}...", name);
        if (indexer != null) {
            try {
                indexer.shutdown();
            } catch (PluginException pe) {
                log.error("Failed to shutdown indexer: {}", pe.getMessage());
                throw pe;
            }
        }
        if (storage != null) {
            try {
                storage.shutdown();
            } catch (PluginException pe) {
                log.error("Failed to shutdown storage: {}", pe.getMessage());
                throw pe;
            }
        }
        for (String queueName : scriptMap.keySet()) {
        	MessagingScript messagingScript = scriptMap.get(queueName);
        	if (messagingScript.consumer != null) {
        		try {
                    messagingScript.consumer.close();
                } catch (JMSException jmse) {
                    log.warn("Failed to close consumer: {}", jmse.getMessage());
                    throw jmse;
                }
        	}

            if (messagingScript.session != null) {
                try {
                	messagingScript.session.close();
                } catch (JMSException jmse) {
                    log.warn("Failed to close consumer session: {}", jmse);
                }
            }
            if (connection != null) {
                try {
                    connection.close();
                } catch (JMSException jmse) {
                    log.warn("Failed to close connection: {}", jmse);
                }
            }
            
            
        }
        if (messaging != null) {
        	messaging.release();
        }      
    }
    /**
     * Does nothing, as all the message handling is handled by the scripts, retained for Fascinator support.
     */
	public void onMessage(Message message) {
        log.debug("MessagingScripting got message, ignoring.");
	}	

	public void setPriority(int newPriority) {
		if (newPriority >= Thread.MIN_PRIORITY
                && newPriority <= Thread.MAX_PRIORITY) {
            thread.setPriority(newPriority);
        }
	}
}
