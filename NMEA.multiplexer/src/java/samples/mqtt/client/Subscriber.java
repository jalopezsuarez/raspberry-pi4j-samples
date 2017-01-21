package samples.mqtt.client;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;

public class Subscriber {

	public static final String BROKER_URL = "tcp://192.168.1.136:1883"; 

	//We have to generate a unique Client id.
	String clientId = "nmea-sub";
	private MqttClient mqttClient;

	public Subscriber() {
		try {
			mqttClient = new MqttClient(BROKER_URL, clientId);
		} catch (MqttException e) {
			e.printStackTrace();
			System.exit(1);
		}
	}

	public void start() {
		try {
			mqttClient.setCallback(new SubscribeCallback());
			mqttClient.connect();
			//Subscribe to all subtopics of home
			final String topic = "nmea/#";
			mqttClient.subscribe(topic);
			System.out.println("Subscriber is now listening to " + topic);
		} catch (MqttException e) {
			e.printStackTrace();
			System.exit(1);
		}
	}

	public static void main(String... args) {
		final Subscriber subscriber = new Subscriber();
		subscriber.start();
	}
}