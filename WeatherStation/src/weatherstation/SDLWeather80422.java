package weatherstation;

import com.pi4j.io.gpio.GpioController;
import com.pi4j.io.gpio.GpioFactory;
import com.pi4j.io.gpio.GpioPinDigitalInput;
import com.pi4j.io.gpio.Pin;
import com.pi4j.io.gpio.RaspiPin;
import com.pi4j.io.gpio.event.GpioPinDigitalStateChangeEvent;
import com.pi4j.io.gpio.event.GpioPinListenerDigital;
import com.pi4j.io.i2c.I2CFactory;
import i2c.adc.ADS1x15;
import i2c.sensor.BMP180;
import i2c.sensor.HTU21DF;
import weatherstation.utils.Utilities;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Board from Switch Doc Labs
 */
public class SDLWeather80422 {

	private final static boolean verbose = "true".equals(System.getProperty("sdl.weather.station.verbose", "false"));

	private final static SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

	final GpioController gpio = GpioFactory.getInstance();

	public enum AdcMode {
		SDL_MODE_INTERNAL_AD,
		SDL_MODE_I2C_ADS1015
	}

	// sample mode means return immediately.
	// The wind speed is averaged at sampleTime or when you ask, whichever is longer.
	// Delay mode means to wait for sampleTime and the average after that time.
	public enum SdlMode {
		SAMPLE,
		DELAY
	}

	private final static double WIND_FACTOR = 2.400;
	private static double wsCoeff = 1.0; // -Dws.wspeed.coeff=1.0
	private static int wdOffset = 0;     // -Dws.wdir.offset=0

	private int currentWindCount = 0;
	private float currentRainCount = 0;
	private long shortestWindMicroTime = Long.MAX_VALUE;

	private long lastWindMilliSecPing = 0;
	private long lastRainMilliSecPing = 0;

	private final static long DEFAULT_MIN_DEBOUNCE_TIME = 3L; // in milli seconds.
	private static long minimumDebounceTimeMilliSec = DEFAULT_MIN_DEBOUNCE_TIME; // -Ddebounce.time.millisec=30

	private GpioPinDigitalInput pinAnem;
	private GpioPinDigitalInput pinRain;
	private AdcMode ADMode = AdcMode.SDL_MODE_I2C_ADS1015;

	private double currentWindSpeed = 0.0;
	private double currentWindDirection = 0.0;

	private long lastWindMicroTime = 0;

	private int sampleTime = 5; // In seconds
	private SdlMode selectedMode = SdlMode.SAMPLE;
	private long startSampleTime = 0L;

	private long currentRainMicroSecMin = 0;
	private long lastRainMicroSecTime = 0;

	private ADS1x15 ads1015 = null;
	private final static ADS1x15.ICType ADC_TYPE = ADS1x15.ICType.IC_ADS1015;
	private int gain = 6_144;
	private int sps  = 250;

	// Other I2C Boards (BMP180, HTU21D-F, MOD-1016, etc)
	private BMP180 bmp180 = null;
	private HTU21DF htu21df = null;
	// TODO? MOD-1016 (lightning detector)

	public SDLWeather80422() {
		this(RaspiPin.GPIO_16, RaspiPin.GPIO_01, AdcMode.SDL_MODE_I2C_ADS1015);
	}

	public SDLWeather80422(Pin anemo, Pin rain, AdcMode ADMode) {
		init(anemo, rain, ADMode);
	}

	private void init(Pin anemo, Pin rain, AdcMode ADMode) {
//  Gpio.add_event_detect(pinAnem, GPIO.RISING, callback=self.serviceInterruptAnem, bouncetime=300)
//  GPIO.add_event_detect(pinRain, GPIO.RISING, callback=self.serviceInterruptRain, bouncetime=300)

		try {
			minimumDebounceTimeMilliSec = Long.parseLong(System.getProperty("debounce.time.millisec", String.valueOf(DEFAULT_MIN_DEBOUNCE_TIME)));
		} catch (NumberFormatException nfe) {
			nfe.printStackTrace();
		}
		if (verbose) {
			System.out.println(String.format("Minimum Debounce Time is set to %d millisec.", minimumDebounceTimeMilliSec));
		}

		try {
			wsCoeff = Double.parseDouble(System.getProperty("ws.wspeed.coeff", Double.toString(wsCoeff)));
		} catch (NumberFormatException nfe) {
			nfe.printStackTrace();
		}
		try {
			wdOffset = Integer.parseInt(System.getProperty("ws.wdir.offset", String.valueOf(wdOffset)));
		} catch (NumberFormatException nfe) {
			nfe.printStackTrace();
		}

		if (anemo != null) {
			this.pinAnem = gpio.provisionDigitalInputPin(anemo, "Anemometer");
			this.pinAnem.addListener(new GpioPinListenerDigital() {
				@Override
				public void handleGpioPinDigitalStateChangeEvent(GpioPinDigitalStateChangeEvent event) {

					long nowMilliSec = System.currentTimeMillis();

					if (verbose) {
						System.out.println(String.format(">>> [%s] Anemo Listener => High: %s, debounce: %s ms (compare to %d), windCount: %d",
								SDF.format(new Date()),
								(event.getState().isHigh()?"yes":"no"),
								NumberFormat.getInstance().format(nowMilliSec - lastWindMilliSecPing),
								minimumDebounceTimeMilliSec,
								currentWindCount));
					}

					if (event.getState().isHigh() && (nowMilliSec - lastWindMilliSecPing) > minimumDebounceTimeMilliSec) {
						long nowMicroSec = Utilities.currentTimeMicros();
						long currentMicroTime = nowMicroSec - lastWindMicroTime;
						lastWindMicroTime = nowMicroSec;

						if (verbose) {
							System.out.println(String.format("\tcurrentTime: %s us, windCount: %d ticks",
									NumberFormat.getInstance().format(currentMicroTime),
									currentWindCount));
						}

						if (currentMicroTime > 1_000) { // debounce, 1 ms.
							if (verbose) {
								System.out.println("\t\tDebounced!");
							}
							currentWindCount += 1;        // Increment Wind tick count
							shortestWindMicroTime = Math.min(shortestWindMicroTime, currentMicroTime);
//							if (currentMicroTime < shortestWindMicroTime) {
//								shortestWindMicroTime = currentMicroTime;
//							}
						}
						lastWindMilliSecPing = nowMilliSec;
						//      System.out.println(" --> GPIO pin state changed: " + System.currentTimeMillis() + ", " + event.getPin() + " = " + event.getState());
					}
				}
			});
		}
		if (rain != null) {
			this.pinRain = gpio.provisionDigitalInputPin(rain, "Rainmeter");
			this.pinRain.addListener(new GpioPinListenerDigital() {
				@Override
				public void handleGpioPinDigitalStateChangeEvent(GpioPinDigitalStateChangeEvent event) {
					long nowMilliSec = System.currentTimeMillis();
					if (event.getState().isHigh() && (nowMilliSec - lastRainMilliSecPing) > minimumDebounceTimeMilliSec) {
						lastRainMicroSecTime = Utilities.currentTimeMicros();
						long currentMicroSecTime = lastRainMicroSecTime - lastRainMicroSecTime;
						if (currentMicroSecTime > 500) { // debounce
							currentRainCount += 1;
							currentRainMicroSecMin = Math.min(currentRainMicroSecMin, currentMicroSecTime);
//							if (currentMicroSecTime < currentRainMicroSecMin) {
//								currentRainMicroSecMin = currentMicroSecTime;
//							}
						}
						lastRainMilliSecPing = nowMilliSec;
						//      System.out.println(" --> GPIO pin state changed: " + System.currentTimeMillis() + ", " + event.getPin() + " = " + event.getState());
					}
				}
			});
		}

		try {
			bmp180 = new BMP180();
		} catch (Exception ex) {
			System.err.println("BMP180 not available...");
		}

		try {
			htu21df = new HTU21DF();
			if (!htu21df.begin()) {
				htu21df = null;
				throw new Exception("HTU21DF not found.");
			}
		} catch (Exception e) {
			System.err.println("HTU21DF not available...");
		}

		try {
			this.ads1015 = new ADS1x15(ADC_TYPE);
		} catch (I2CFactory.UnsupportedBusNumberException usbne) {
			throw new RuntimeException(usbne);
		}
		this.ADMode = ADMode;
	}

	public void resetRainTotal() {
		this.currentRainCount = 0f;
	}

	public float getCurrentRainTotal() {
		float rainAmount = 0.2794f * this.currentRainCount / 2f;
		resetRainTotal();
		return rainAmount;
	}

	// Wind Direction Routines
	public float getCurrentWindDirection() {
		double direction = Utilities.voltageToDegrees(getCurrentWindDirectionVoltage(), this.currentWindDirection);
		direction += wdOffset;
		while (direction > 360) {
			direction -= 360;
		}
		while (direction < 0) {
			direction += 360;
		}
		this.currentWindDirection = direction;

		return (float) direction;
	}

	public double getCurrentWindDirectionVoltage() {
		double voltage = 0f;
		if (this.ADMode == AdcMode.SDL_MODE_I2C_ADS1015) {
			float value = ads1015.readADCSingleEnded(
					ADS1x15.Channels.CHANNEL_1,
					this.gain,
					this.sps); // AIN1 wired to wind vane on WeatherPiArduino
//    System.out.println("Voltage Value:" + value);
			voltage = value / 1_000f;
		} else {
			// user internal A/D converter
			voltage = 0.0f;
		}
		return voltage;
	}

	public void setWindMode(SdlMode selectedMode, int sampleTime) { // time in seconds
		this.sampleTime = sampleTime;
		this.selectedMode = selectedMode;
		if (this.selectedMode == SdlMode.SAMPLE) {
			this.startWindSample(this.sampleTime);
		}
	}

	public void startWindSample(int sampleTime) {
		this.startSampleTime = Utilities.currentTimeMicros();
		this.sampleTime = sampleTime;
	}

	public double getCurrentWindSpeedWhenSampling() {
		double compareValue = this.sampleTime * 1_000_000; // micro-seconds
		long nowMicro = Utilities.currentTimeMicros();
		if (verbose) {
			System.out.println(String.format(">>> [%s] method getCurrentWindSpeedWhenSampling, compareValue = %s, nowMicro = %s",
					SDF.format(new Date()),
					NumberFormat.getInstance().format(Math.round(compareValue)),
					NumberFormat.getInstance().format(nowMicro)));
		}
		if ((nowMicro - this.startSampleTime) >= compareValue) { // Sample is now big enough.
			// sample time exceeded, calculate currentWindSpeed
			long timeSpan = (nowMicro - this.startSampleTime);
			this.currentWindSpeed = (float) ((this.currentWindCount) / (float) timeSpan) * WIND_FACTOR * wsCoeff * 1_000_000.0;
      if (verbose) {
	      System.out.printf(">>>\tmethod getCurrentWindSpeedWhenSampling, WindSpeed = %f, shortestWindMicroTime = %d, CWCount = %d (WindTicks per Sec) = %f\n",
			      this.currentWindSpeed,
			      this.shortestWindMicroTime,
			      this.currentWindCount,
			      (float) this.currentWindCount / (float) timeSpan);
      }
      // Reset
			this.currentWindCount = 0;
			this.startSampleTime = nowMicro;
		} else if (verbose) { // Sample not big enough yet.
			System.out.println(String.format(">>>\tgetCurrentWindSpeedWhenSampling still waiting for a bigger sample. WindSpeed currently %f",
					this.currentWindSpeed));
		}
		return this.currentWindSpeed;
	}

	public double currentWindSpeed() {
		if (verbose) {
			System.out.println(String.format(">>> [%s] Method currentWindSpeed >> Mode: %s",
					SDF.format(new Date()),
					this.selectedMode.toString()));
		}
		if (this.selectedMode == SdlMode.SAMPLE) {
			this.currentWindSpeed = this.getCurrentWindSpeedWhenSampling();
		} else {
			// km/h * 1000 msec
			this.currentWindCount = 0;
			try {
				Thread.sleep(Math.round(this.sampleTime * 1_000L));
			} catch (Exception ex) {
				ex.printStackTrace();
			}
			this.currentWindSpeed = ((float) (this.currentWindCount) / (float) (this.sampleTime)) * WIND_FACTOR * wsCoeff;
			if (verbose) {
				System.out.println(String.format("\t>>> Method currentWindSpeed, Count: %d, SampleTime: %d, => WindSpeed: %f",
						this.currentWindCount,
						this.sampleTime,
						this.currentWindSpeed));
			}
		}
		return this.currentWindSpeed;
	}

	public void resetWindGust() {
		this.shortestWindMicroTime = Long.MAX_VALUE;
	}

	public double getWindGust() {
		long latestTime = this.shortestWindMicroTime;
		this.resetWindGust();
		if (latestTime != Long.MAX_VALUE && latestTime > 30_000) { // 30_000: 30ms debounce
			double time = latestTime / 1_000_000d;  // in seconds
//  System.out.println("WindGust: Latest:" + latestTime + ", time:" + time);
			if (time == 0d) {
				return 0;
			} else {
				double gust = (1.0 / time) * WIND_FACTOR * wsCoeff;
				if (verbose) {
					if (gust > 50) { // This is suspicious
						System.out.println(String.format("Gust-> latestTime:%d, time: %f, gust: %f", latestTime, time, gust));
					}
				}
				return gust;
			}
		} else {
			return 0;
		}
	}

	public boolean isBMP180Available() {
		return bmp180 != null;
	}

	public float readTemperature() throws Exception {
		float temp = bmp180.readTemperature();
		return temp;
	}

	public float readPressure() throws Exception {
		float pr = bmp180.readPressure();
		return pr;
	}

	public boolean isHTU21DFAvailable() {
		return htu21df != null;
	}

	public float readHumidity() throws Exception {
		return htu21df.readHumidity();
	}

	public void shutdown() {
		gpio.shutdown();
	}

	/**
	 * km/h to mph
	 *
	 * @param val
	 * @return
	 */
	public static double toMPH(double val) {
		return val / 1.609;
	}

	/**
	 * km/h to knots
	 *
	 * @param val
	 * @return
	 */
	public static double toKnots(double val) {
		return val / 1.852;
	}

	/**
	 * mm to inches
	 *
	 * @param val value in mm
	 * @return value in inches
	 */
	public static double toInches(double val) {
		return val / 25.4;
	}
}
