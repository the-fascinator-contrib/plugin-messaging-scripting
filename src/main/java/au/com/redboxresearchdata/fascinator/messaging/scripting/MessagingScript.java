/*******************************************************************************
 * Copyright (C) 2015 Queensland Cyber Infrastructure Foundation (http://www.qcif.edu.au/)
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
package au.com.redboxresearchdata.fascinator.messaging.scripting;

import java.io.*;

import javax.script.*;
import javax.jms.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.googlecode.fascinator.common.JsonSimple;

/**
 * Launches scripts in response to a JMS message.
 *
 * @author <a target='_' href='https://github.com/shilob'>Shilo Banihit</a>
 *
 */
public class MessagingScript implements MessageListener {
	/** Queue Name */
	public String destName;
	public String destType;
	/** File */
	public File file;
	/** Message Consumer instance */
    public MessageConsumer consumer;
    /** Message Producer instance */
    public MessageProducer producer;
    /** JMS Session */
    public Session session;
    public Bindings bindings;
    public JsonSimple config;
    public String scriptEngineName;
    
    private static Logger log = LoggerFactory.getLogger(MessagingScript.class);
    private ScriptEngine engine;
	/**
	 * Handles the incoming message by launching the script, binding the text payload as 'message'.
	 */
	@Override
	public void onMessage(Message message) {
		try {
        	String text = ((TextMessage) message).getText();
        	log.info(destName + ", got message: " + text);
        	if (engine == null) {
        		ScriptEngineManager manager = new ScriptEngineManager();
                engine = manager.getEngineByName(scriptEngineName);
                engine.setBindings(bindings, ScriptContext.ENGINE_SCOPE);
                engine.put("config", config);
        	}
        	FileReader fileReader = new FileReader(file); // reads a fresh script file everytime, no caching.
        	engine.put("message", text);
        	log.debug("Running script: {}", file.getAbsolutePath());
        	engine.eval(fileReader);
        	log.debug("Done running: {}", file.getAbsoluteFile());
        } catch (JMSException jmse) {
            log.error("Failed to send/receive message: {}", jmse.getMessage());
        } catch (IOException ioe) {
            log.error("Failed to run script, IOException: {}", ioe.getMessage());
        } catch (Exception ex) {
        	log.error("Failed to run script: {}", ex.getMessage());
        	log.error("Stack trace:", ex);
        }
	}
    
	
    
}
