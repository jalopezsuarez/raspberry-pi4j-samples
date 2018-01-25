package nmea.forwarders;

import http.client.HTTPClient;
import nmea.parser.StringGenerator;
import nmea.parser.StringParsers;
import org.json.JSONObject;
import util.TextToSpeech;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * This is a {@link Forwarder}, forwarding chosen data to a REST server (POST).
 * In this case this is Adafruit.IO
 * <br>
 *   Data are (can be)
 *   - Air Temperature
 *   - Atmospheric pressure
 *   - Humidity
 *   - Wind Speed
 *   - Wind direction
 *   - Rain (precipitation rate)
 * <br>
 * It must be loaded dynamically. As such, it can be set only from the properties file
 * used at startup. It - for now - cannot be managed from the Web UI.
 * The REST api is not aware of it.
 * <br>
 * To load it, use the properties file at startup:
 * <pre>
 *   forward.XX.cls=nmea.forwarders.RESTPublisher
 *   forward.XX.properties=rest.server.properties
 * </pre>
 * A jar containing this class and its dependencies must be available in the classpath.
 *
 * // TODO Logging rate, per channel.
 */
public class RESTPublisher implements Forwarder {

	private static String AIR_TEMP   = "air-temperature";
	private static String ATM_PRESS  = "atm-press";
	private static String HUMIDITY   = "humidity";

	private Properties properties;

	private final static long ONE_MINUTE = 60 * 1_000;

	private long INTERVAL = ONE_MINUTE;
	private long previousTempLog = 0;
	private long previousHumLog = 0;
	private long previousPressLog = 0;

	/*
	 * @throws Exception
	 */
	public RESTPublisher() throws Exception {
	}

	private void setFeedValue(String key, String baseUrl, String feed, String value) throws Exception {
		System.out.println(String.format("URL:%s, key:%s", baseUrl, key));
		String url = baseUrl + "/api/feeds/" + feed + "/data";
		Map<String, String> headers = new HashMap<>(1);
		headers.put("X-AIO-Key", key);
		JSONObject json = new JSONObject();
		json.put("value", new Double(value));
		System.out.println("POSTing " + json.toString(2) + " to " + url);
		HTTPClient.HTTPResponse response = HTTPClient.doPost(url, headers, json.toString());
		if (response.getCode() > 299) {
			System.out.println(String.format("POST Ret: %d, %s", response.getCode(), response.getPayload()));
		}
	}

	@Override
	public void write(byte[] message) {
		String str = new String(message);
//	System.out.println(">>>> Mess:" + str);
		if (StringParsers.validCheckSum(str)) {
//		String deviceId = StringParsers.getDeviceID(str);
			String sentenceId = StringParsers.getSentenceID(str);
			if ("MDA".equals(sentenceId)) {
				StringParsers.MDA mda = StringParsers.parseMDA(str);
//				System.out.println(String.format(
//						"MDA >> Hum: %.02f%%, AirT: %.02f\272, PRMSL: %.02f mB, TWD: %.02f\272, TWS: %.02f kts",
//						mda.relHum,
//						mda.airT,
//						mda.pressBar * 1_000,
//						mda.windDirM,
//						mda.windSpeedK));
			} else if ("XDR".equals(sentenceId)) {
				List<StringGenerator.XDRElement> xdrElements = StringParsers.parseXDR(str);
				xdrElements.stream().forEach(xdr -> {
//				System.out.println(String.format("XDR: %s -> %s", xdr.getTypeNunit(), xdr.toString()));
					String url = this.properties.getProperty("aio.url");
					if (xdr.getTypeNunit().equals(StringGenerator.XDRTypes.TEMPERATURE)) {
						String feed = AIR_TEMP;
						long now = System.currentTimeMillis();
						if (Math.abs(now - previousTempLog) > INTERVAL) {
							try {
								setFeedValue(this.properties.getProperty("aio.key"), url, feed, String.valueOf(xdr.getValue()));
								previousTempLog = now;
							} catch (Exception ex) {
								ex.printStackTrace();
							}
						}
					} else if (xdr.getTypeNunit().equals(StringGenerator.XDRTypes.HUMIDITY)) {
						String feed = HUMIDITY;
						long now = System.currentTimeMillis();
						if (Math.abs(now - previousHumLog) > INTERVAL) {
							try {
								setFeedValue(this.properties.getProperty("aio.key"), url, feed, String.valueOf(xdr.getValue()));
								previousHumLog = now;
							} catch (Exception ex) {
								ex.printStackTrace();
							}
						}
					} else if (xdr.getTypeNunit().equals(StringGenerator.XDRTypes.PRESSURE_P)) {
						String feed = ATM_PRESS;
						long now = System.currentTimeMillis();
						if (Math.abs(now - previousPressLog) > INTERVAL) {
							try {
								setFeedValue(this.properties.getProperty("aio.key"), url, feed, String.valueOf(xdr.getValue() / 100));
								previousPressLog = now;
							} catch (Exception ex) {
								ex.printStackTrace();
							}
						}
					}
				});
			}
		}
	}

	@Override
	public void close() {
		System.out.println("- Stop writing (REST-> Adafruit-IO) to " + this.getClass().getName());
	}

	public static class RESTBean {
		private String cls;
		private String type = "REST-forwarder";

		public RESTBean(RESTPublisher instance) {
			cls = instance.getClass().getName();
		}
	}

	@Override
	public Object getBean() {
		return new RESTBean(this);
	}

	@Override
	public void setProperties(Properties props) {
		this.properties = props;
	}
}
