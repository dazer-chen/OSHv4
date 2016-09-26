package osh.busdriver.mielegateway;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import osh.busdriver.mielegateway.data.MieleDeviceHomeBusDataJSON;
import osh.core.logging.IGlobalLogger;
import rx.Scheduler;
import rx.Subscription;
import rx.schedulers.Schedulers;
import ws.wamp.jawampa.WampClient;
import ws.wamp.jawampa.WampClientBuilder;
import ws.wamp.jawampa.connection.IWampConnectorProvider;
import ws.wamp.jawampa.transport.netty.NettyWampClientConnectorProvider;

/**
 * Handling the connection to one miele gateway
 * 
 * @author Kaibin Bao, Ingo Mauser
 *
 */
public class MieleGatewayWAMPDispatcher {

	private IGlobalLogger logger;

	private String url = "ws://wamp-router:8080/ws";
	private String realm = "eshl";
//	private String realm = "realm1";

	private WampClient client;
	private ObjectMapper mapper;

	Subscription onDataSubscription;

	// Scheduler
	ExecutorService executor = Executors.newSingleThreadExecutor();
	Scheduler rxScheduler = Schedulers.from(executor);

	// (Miele) UID (sic!) -> device state map
	private Map<Integer, MieleDeviceHomeBusDataJSON> deviceData;

	/**
	 * CONSTRUCTOR
	 * 
	 * @param logger
	 * @param address
	 * @throws MalformedURLException
	 */
	public MieleGatewayWAMPDispatcher(IGlobalLogger logger) {
		super();

		this.logger = logger;

		this.deviceData = new HashMap<Integer, MieleDeviceHomeBusDataJSON>();

		this.mapper = new ObjectMapper();
		
		subscribeForWampUpdates();
	}

	/**
	 * Collect information about Miele device and provide it (to
	 * MieleGatewayDriver)
	 * 
	 * @param id
	 *            Miele UID (sic!)
	 * @return
	 */
	public MieleDeviceHomeBusDataJSON getDeviceData(Integer id) {
		MieleDeviceHomeBusDataJSON dev;
		synchronized (this) {
			dev = deviceData.get(id);
		}
		return dev;
	}

	/**
	 * Collect all information about Miele devices and provide it (to
	 * MieleGatewayDriver)
	 * 
	 * @return
	 */
	public Collection<MieleDeviceHomeBusDataJSON> getDeviceData() {
		ArrayList<MieleDeviceHomeBusDataJSON> devices = new ArrayList<MieleDeviceHomeBusDataJSON>();
		synchronized (this) {
			devices.addAll(deviceData.values());
		}
		return devices;
	}

	public void sendCommand(String command, int uid) {
		client.call("eshl.miele.v1.homebus." + command, uid)
		      .observeOn(rxScheduler)
		      .subscribe( response -> {
		    	  logger.logInfo(response);
		      }, err -> {
		    	  logger.logError("sending command failed", err);
		      });
	}
	
	public void sendStarttimes(long starttime, int uid) {
		HashMap<Integer, Long> publishMap = new HashMap<Integer, Long>();
		publishMap.put(uid, starttime);
		client.publish("eshl.schedules", publishMap, new TypeReference<Map<Integer, Long>>(){})
		      .observeOn(rxScheduler)
		      .subscribe( response -> {
		    	  logger.logInfo(response);
		      }, err -> {
		    	  logger.logError("publishing startTime failed", err);
		      });
	}

	public void subscribeForWampUpdates() {
        WampClientBuilder builder = new WampClientBuilder();
        try {
        	IWampConnectorProvider connectorProvider = new NettyWampClientConnectorProvider();
            builder.withConnectorProvider(connectorProvider)
            .withUri(url)
            .withRealm(realm)
            .withInfiniteReconnects()
            .withReconnectInterval(3, TimeUnit.SECONDS);
            
            client = builder.build();
        } 
        catch (Exception e) {
        	e.printStackTrace();
            return;
        }

        // Subscribe on the clients status updates
        client.statusChanged()
              .observeOn(rxScheduler)
              .subscribe(
            	  state -> {
            		  logger.logInfo("Session status changed to " + state);
            		  if (state instanceof WampClient.ConnectedState) {
						// SUBSCRIBE to a topic and receive events
            			  onDataSubscription = client.makeSubscription("eshl.miele.v1.homebus")
						                             .observeOn(rxScheduler)
						                             .subscribe(
                             ev -> {
                                 if (ev.arguments() == null || ev.arguments().size() < 1)
                                     return; // error

                                 JsonNode eventNode = ev.arguments().get(0);
                                 if (eventNode.isNull()) return;

                                 try {
                                	 Map<Integer, MieleDeviceHomeBusDataJSON> map =
                                			 mapper.convertValue(eventNode, new TypeReference<Map<Integer, MieleDeviceHomeBusDataJSON>>(){});

                                	 synchronized (this) {
    									 deviceData.clear();
    									 deviceData.putAll(map);
    								     this.notifyAll();
    								 }
                                 } catch (IllegalArgumentException e) {
                                     return; // error
                                 }
							 },
                             e -> {
                            	 logger.logError("failed to subscribe to topic", e);
						     }, 
                             () -> {
                            	 logger.logInfo("subscription ended");
						     });				
		                }
		                else if (state instanceof WampClient.DisconnectedState) {
		            		if (onDataSubscription != null)
		            			onDataSubscription.unsubscribe();
		            		onDataSubscription = null;
		                }
		        },
            	t -> {
	            	logger.logError("Session ended with error ", t);
		        },
            	() -> {
	                logger.logInfo("Session ended normally");
		        });

        client.open();
	}

	public String convertStreamToString(InputStream is) throws IOException {
		// To convert the InputStream to String we use the
		// Reader.read(char[] buffer) method. We iterate until the
		// Reader return -1 which means there's no more data to
		// read. We use the StringWriter class to produce the string.
		if (is != null) {
			Writer writer = new StringWriter();

			char[] buffer = new char[1024];
			try {
				Reader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
				int n;
				while ((n = reader.read(buffer)) != -1) {
					writer.write(buffer, 0, n);
				}
			} finally {
				is.close();
			}
			return writer.toString();
		}
		return "";
	}
}
