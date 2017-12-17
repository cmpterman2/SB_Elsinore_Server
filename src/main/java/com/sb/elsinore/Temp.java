package com.sb.elsinore;
import com.sb.elsinore.devices.I2CDevice;
import com.sb.util.MathUtil;
import javax.annotation.Nonnull;
import jGPIO.GPIO.Direction;
import jGPIO.InPin;
import jGPIO.InvalidGPIOException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.owfs.jowfsclient.OwfsException;

/**
 * Temp class is used to monitor temperature on the one wire system.
 * @author Doug Edey
 *
 */
public final class Temp implements Runnable, Comparable<Temp> {

    /**
     * Strings for the Nodes.
     */
    public static final String PROBE_SIZE = "ProbeSize";
    public static final String PROBE_ELEMENT = "probe";
    public static final String POSITION = "position";

    /**
     * Valid sizes for the probes
     */
    public static int SIZE_SMALL = 0;
    public static int SIZE_MEDIUM = 1;
    public static int SIZE_LARGE = 2;

    /**
     * Magic numbers.
     * F_TO_C_MULT: Multiplier to convert F to C.
     * C_TO_F_MULT: Multiplier to convert C to F.
     * FREEZING: The freezing point of Fahrenheit.
     */
    public MathContext context = new MathContext(2, RoundingMode.HALF_DOWN);
    public static BigDecimal FREEZING = new BigDecimal(32);
    public static BigDecimal ERROR_TEMP = new BigDecimal(-999);
    private boolean badTemp = false;
    private boolean keepalive = true;
    private boolean hidden = false;
    public boolean cutoffEnabled = false;

    /**
     * Base path for BBB System Temp.
     */
    private final String bbbSystemTemp =
            "/sys/class/hwmon/hwmon0/device/temp1_input";
    /**
     * Base path for RPi System Temp.
     */
    private final String rpiSystemTemp =
            "/sys/class/thermal/thermal_zone0/temp";
    /**
     * Match the temperature regexp.
     */
    private final Pattern tempRegexp = Pattern.compile("^(-?)([0-9]+)(.[0-9]{1,2})?$");
    private int size = SIZE_LARGE;
    public I2CDevice i2cDevice = null;
    public int i2cChannel = -1;

    /**
     * Save the current object to the configuration using LaunchControl.
     */
    public void save() {
        if (name != null && !name.equals("")) {
            LaunchControl.addTempToConfig(this);
        }
    }

    /**
     * Standard constructor.
     * @param name The probe name or input name.
     *  Use "system" to create a system temperature probe
     * @param inProbe The address of this temperature probe.
     */
    public Temp(String name, String inProbe) {

        String aName = inProbe;
        BrewServer.LOG.info("Adding" + aName);
        if (name.equalsIgnoreCase("system")) {
            aName = "System";
            File tempFile = new File(rpiSystemTemp);
            if (tempFile.exists()) {
                fProbe = rpiSystemTemp;
            } else {
                tempFile = new File(bbbSystemTemp);
                if (tempFile.exists()) {
                    fProbe = bbbSystemTemp;
                } else {
                    BrewServer.LOG.warning(
                            "Couldn't find a valid system temperature probe");
                    return;
                }
            }
        }
        else if (name.equalsIgnoreCase("Blank")){
            // This is a special case for no temp probe.
            fProbe = null;
        } else {
            fProbe = "/sys/bus/w1/devices/" + aName + "/w1_slave";
            File probePath =
                new File(fProbe);

            // Lets assume that OWFS has "." separated names
            if (!probePath.exists() && aName.contains(".")) {
                String[] newAddress = aName.split("\\.|-");

                if (newAddress.length == 2) {
                    String devFamily = newAddress[0];
                    String devAddress = "";
                    // Byte swap!
                    devAddress += newAddress[1].subSequence(10, 12);
                    devAddress += newAddress[1].subSequence(8, 10);
                    devAddress += newAddress[1].subSequence(6, 8);
                    devAddress += newAddress[1].subSequence(4, 6);
                    devAddress += newAddress[1].subSequence(2, 4);
                    devAddress += newAddress[1].subSequence(0, 2);

                    String fixedAddress = devFamily + "-"
                        + devAddress.toLowerCase();

                    BrewServer.LOG.info("Converted address: " + fixedAddress);

                    this.fProbe = "/sys/bus/w1/devices/" + fixedAddress + "/w1_slave";
                    probePath = new File(fProbe);
                    if (!probePath.exists())
                    {
                        // Try OWFS
                        fProbe = null;
                    }
                }
            }

        }

        this.probeName = aName;
        this.name = name;
        BrewServer.LOG.info(this.probeName + " added.");
    }

    /**
     * The Runnable loop.
     */
    public void run() {

        while (keepalive) {
            if (updateTemp().equals(ERROR_TEMP)) {
                if (fProbe != null && fProbe.equals(
                        "/sys/class/thermal/thermal_zone0/temp")) {
                    return;
                }
                // Uh(oh no file found, disable output to prevent logging floods
                loggingOn = false;
            } else {
                loggingOn = true;
            }

            if (volumeMeasurement) {
                updateVolume();
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * @param n The name to set this Temp to.
     */
    public void setName(final String n) {
        this.name = n;
    }

    /**
     * @return The name of this probe
     */
    public String getName() {
        return name.replaceAll(" ", "_");
    }

    /**
     * @return The address of this probe
     */
    public String getProbe() {
        return probeName;
    }

    /******
     * Method to take a cutoff value, parse it to the correct scale
     *  and then update the cutoffTemp.
     * @param cutoffInput String describing the temperature
     */
    public void setCutoffTemp(final String cutoffInput) {

        Matcher tempMatcher = tempRegexp.matcher(cutoffInput);

        if (tempMatcher.find()) {
            // We have matched against the TEMP_REGEXP
            String number = tempMatcher.group(1);
            if (number == null) {
                number = "+";
            }
            // Get the integer
            if (tempMatcher.group(2) != null) {
                number += tempMatcher.group(2);
            }
            // Do we have a decimal?
            if (tempMatcher.group(3) != null) {
                number += tempMatcher.group(3);
            }
            // Create the temp
            this.cutoffTemp= new BigDecimal(number);
        } else {
            BrewServer.LOG.severe(cutoffTemp + " doesn't match "
                    + tempRegexp.pattern());
        }
    }

    // PRIVATE ////
    /**
     * Setup strings for the probe.
     */
    private String fProbe, name, probeName;
    /**
     * Turn on and off logging.
     */
    private boolean loggingOn = true;
    /**
     * Hold the current error string.
     */
    public String currentError = null;

    /**
     * The current temp.
     */
    private BigDecimal currentTemp = new BigDecimal(0),
            currentVolume = new BigDecimal(0),
            cutoffTemp = new BigDecimal(-999.0),
            volumeConstant = new BigDecimal(0),
            volumeMultiplier = new BigDecimal(0.0),
            gravity = new BigDecimal(1.000);

    /**
     * The current timestamp.
     */
    private long currentTime = 0;
    /**
     * Other strings, obviously named.
     */
    private String scale = "C", volumeAddress = "", volumeOffset = "",
            volumeUnit = "";

    /**
     * Are we measuring volume?
     */
    private boolean volumeMeasurement = false;
    /**
     * Volume analog input number.
     */
    private int volumeAIN = -1;
    /**
     * The baselist of volume measurements.
     */
    private ConcurrentHashMap<BigDecimal, BigDecimal> volumeBase = null;

    /**
     * The input pin to read.
     */
    private InPin volumePin = null;
    private boolean stopVolumeLogging;
    private BigDecimal calibration = BigDecimal.ZERO;
    private TriggerControl triggerControl = null;
    private int position = -1;

    /**
     * @return Get the current temperature
     */
    public BigDecimal getTemp() {
        // set up the reader
        if (scale.equals("F")) {
            return getTempF();
        }
        return getTempC();
    }

    /**
     * @return The temp unit/Scale
     */
    public String getScale() {
        return scale;
    }

    /**
     * @param s Value to set the temperature unit to.
     */
    public void setScale(final String s) {
        BrewServer.LOG.warning("Cut off is: " + this.cutoffTemp);

        if (s.equalsIgnoreCase("F")) {
            // Do we need to convert the cutoff temp
            if (cutoffTemp.compareTo(ERROR_TEMP) != 0
                    && !scale.equalsIgnoreCase(s)) {
                this.cutoffTemp = cToF(cutoffTemp);
            }

            this.calibration = this.calibration.multiply(new BigDecimal(1.8));

            this.scale = s;
        }

        if (s.equalsIgnoreCase("C")) {
            // Do we need to convert the cutoff temp
            if (cutoffTemp.compareTo(ERROR_TEMP) != 0
                    && !scale.equalsIgnoreCase(s)) {
                this.cutoffTemp = fToC(cutoffTemp);
            }
            this.calibration = this.calibration.divide(new BigDecimal(1.8), context);
            this.scale = s;
        }
        BrewServer.LOG.warning("Cut off is now: " + this.cutoffTemp);
    }

    /**
     * @return The current temperature in fahrenheit.
     */
    public BigDecimal getTempF() {
        if (scale.equals("F")) {
            return currentTemp.add(this.calibration);
        }
        return cToF(currentTemp.add(this.calibration));
    }

    /**
     * @return The current temperature in celsius.
     */
    public BigDecimal getTempC() {
        if (scale.equals("C")) {
            return currentTemp.add(this.calibration);
        }
        return fToC(currentTemp.add(this.calibration));
    }

    /**
     * @param currentTemp temperature to convert in Fahrenheit
     * @return Temperature in celsius
     */
    public static BigDecimal fToC(final BigDecimal currentTemp) {
        BigDecimal t = currentTemp.subtract(FREEZING);
        t = MathUtil.divide(MathUtil.multiply(t, 5),9);
        return t;
    }

    /**
     * @param currentTemp temperature to convert in Celsius
     * @return Temperature in Fahrenheit
     */
    public static BigDecimal cToF(final BigDecimal currentTemp) {
        BigDecimal t = MathUtil.divide(MathUtil.multiply(currentTemp, 9), 5);
        t = t.add(FREEZING);
        return t;
    }

    /**
     * @return The current timestamp.
     */
    public long getTime() {
        return currentTime;
    }
    
    /**
     * @return The current cutoff temp.
     */
    public String getCutoff() {
        return cutoffTemp.toPlainString();
    }

    /**
     * @return The current temperature as read. -999 if it's bad.
     */
    public BigDecimal updateTemp() {
        BigDecimal result;

        if (badTemp && currentError != null && currentError.equals("")) {
            BrewServer.LOG.warning("Trying to recover " + this.getName());
        }
        if (fProbe == null) {
            result = updateTempFromOWFS();
        } else {
            result = updateTempFromFile();
        }

        if (result.equals(ERROR_TEMP)) {
            badTemp = true;
            return result;
        }

        if (badTemp) {
            badTemp = false;
            BrewServer.LOG.warning("Recovered temperature reading for " + this.getName());
            if (this.currentError.startsWith("Could"))
            {
                this.currentError = "";
            }
        }

        // OWFS/One wire always uses Celsius
        if (scale.equals("F")) {
            result = cToF(result);
        }

        currentTemp = result;
        currentTime = System.currentTimeMillis();
        currentError = null;

        if (cutoffEnabled
                && currentTemp.compareTo(cutoffTemp) >= 0) {
            BrewServer.LOG.log(Level.SEVERE,
                currentTemp + ": ****** CUT OFF TEMPERATURE ("
                + cutoffTemp + ") EXCEEDED *****");
            System.exit(-1);
        }
        return result;
    }

    /**
     * @return Get the current temperature from the OWFS server
     */
    public BigDecimal updateTempFromOWFS() {
        // Use the OWFS connection
        if (probeName.equals("Blank")) { return new BigDecimal(0.0);}
        BigDecimal temp = ERROR_TEMP;
        String rawTemp = "";
        try {
            rawTemp = LaunchControl.readOWFSPath(probeName + "/temperature");
            if (rawTemp.equals("")) {
                BrewServer.LOG.severe(
                    "Couldn't find the probe " + probeName + " for " + name);
                LaunchControl.setupOWFS();
            } else {
                temp = new BigDecimal(rawTemp);
            }
        } catch (IOException e) {
            currentError = "Couldn't read " + probeName;
            BrewServer.LOG.log(Level.SEVERE, currentError, e);
        } catch (OwfsException e) {
            currentError = "Couldn't read " + probeName;
            BrewServer.LOG.log(Level.SEVERE, currentError, e);
            LaunchControl.setupOWFS();
        } catch (NumberFormatException e) {
            currentError = "Couldn't parse" + rawTemp;
            BrewServer.LOG.log(Level.SEVERE, currentError, e);
        }

        return temp;
    }

    /**
     * @return The current temperature read directly from the file system.
     */
    public BigDecimal updateTempFromFile() {
        BufferedReader br = null;
        String temp = null;
        
        BigDecimal newTemperature = null;

        try {
            br = new BufferedReader(new FileReader(fProbe));
            String line = br.readLine();
            
            if (line == null || line.contains("NO")) {
                // bad CRC, do nothing
                this.currentError = "Bad CRC from " + fProbe;
            } else if (line.contains("YES")) {
                // good CRC
                line = br.readLine();
                // last value should be t=
                int t = line.indexOf("t=");
                temp = line.substring(t + 2);
                BigDecimal tTemp = new BigDecimal(temp);
                newTemperature = MathUtil.divide(tTemp, 1000);
                this.currentError = null;
            } else {
                // System Temperature
                BigDecimal tTemp = new BigDecimal(line);
                newTemperature = MathUtil.divide(tTemp, 1000);
            }

        } catch (IOException ie) {
            if (loggingOn) {
                this.currentError = "Couldn't find the device under: " + fProbe;
                BrewServer.LOG.warning(currentError);
                if (fProbe.equals(rpiSystemTemp)) {
                    fProbe = bbbSystemTemp;
                }
            }
            return ERROR_TEMP;
        } catch (NumberFormatException nfe) {
            this.currentError = "Couldn't parse " + temp + " as a double";
            nfe.printStackTrace();
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException ie) {
                    BrewServer.LOG.warning(ie.getLocalizedMessage());
                }
            }
        }
        if( newTemperature == null )
        {
            newTemperature = getTempC();
        }
        return newTemperature;
    }

    /**
     * Setup the volume reading.
     * @param address One Wire device address.
     * @param offset Device offset input.
     * @param unit The volume unit to read as.
     */
    public boolean setupVolumes(final String address, final String offset,
            final String unit) {
        // start a volume measurement at the same time
        if (unit == null
            || (!unit.equals(VolumeUnits.LITRES)
            && !unit.equals(VolumeUnits.UK_GALLONS)
            && !unit.equals(VolumeUnits.US_GALLONS))) {
            return false;
        }

        this.volumeMeasurement = true;
        this.volumeUnit = unit;
        this.volumeAddress = address.replace("-", ".");
        this.volumeOffset = offset.toUpperCase();

        try {
            BrewServer.LOG.log(Level.INFO,
                "Volume ADC at: " + volumeAddress + " - " + offset);
            String temp =
                LaunchControl.readOWFSPath(volumeAddress + "/volt." + offset);

            // Check to make sure we can read OK
            if (temp.equals("")) {
                BrewServer.LOG.severe(
                    "Couldn't read the Volume from " + volumeAddress
                    + "/volt." + offset);
                return false;
            } else {
                BrewServer.LOG.log(Level.INFO, "Volume reads " + temp);
            }
        } catch (IOException e) {
            BrewServer.LOG.log(Level.SEVERE,
                "IOException when access the ADC over 1wire", e);
            return false;
        } catch (OwfsException e) {
            BrewServer.LOG.log(Level.SEVERE,
                    "OWFSException when access the ADC over 1wire", e);
            return false;
        }

        return true;
    }

    /**
     * Setup volumes for AIN pins.
     * @param analogPin The AIN pin number
     * @param unit The volume unit
     * @return True is setup OK
     * @throws InvalidGPIOException If the Pin cannot be setup
     */
    public boolean setupVolumes(final int analogPin, final String unit)
            throws InvalidGPIOException {
        // start a volume measurement at the same time
        if (unit == null
            || (!unit.equals(VolumeUnits.LITRES)
            && !unit.equals(VolumeUnits.UK_GALLONS)
            && !unit.equals(VolumeUnits.US_GALLONS))) {
            return false;
        }

        this.volumeMeasurement = true;
        this.volumeUnit = unit;

        try {
            this.volumePin = new InPin(analogPin, Direction.ANALOGUE);
        } catch (InvalidGPIOException e) {
            this.volumeMeasurement = false;
            BrewServer.LOG.warning("Invalid Analog GPIO specified " + analogPin);
            throw(e);
        }

        setupVolume();

        if (volumeConstant.compareTo(BigDecimal.ZERO) >= 0
                || volumeMultiplier.compareTo(BigDecimal.ZERO) >= 0) {
            this.volumeMeasurement = false;
        }

        return this.volumeMeasurement;

    }

    /**
     * Calculate volume reading maths.
     */
    public void setupVolume() {
        if (volumeBase == null) {
            return;
        }

        // Calculate the values of b*value + c = volume
        // get the value of c
        this.volumeMultiplier = new BigDecimal(0);
        this.volumeConstant = new BigDecimal(0);

        // for the rest of the values
        Iterator<Entry<BigDecimal, BigDecimal>> it = volumeBase.entrySet().iterator();
        Entry<BigDecimal, BigDecimal> prevPair = null;

        while (it.hasNext()) {
            Entry<BigDecimal, BigDecimal> pairs = it.next();
            if (prevPair != null) {
                // diff the pair value and dive by the diff of the key
                BigDecimal keyDiff = pairs.getKey().subtract(prevPair.getKey());
                BigDecimal valueDiff =
                        pairs.getValue().subtract(prevPair.getValue());
                BigDecimal newMultiplier = MathUtil.divide(valueDiff, keyDiff);
                BigDecimal newConstant =
                        pairs.getValue().subtract(valueDiff.multiply(keyDiff));

                if (volumeMultiplier.compareTo(BigDecimal.ZERO) != 0) {
                    if (newMultiplier.equals(volumeMultiplier)) {
                        BrewServer.LOG.info(
                            "The newMultiplier isn't the same as the old one,"
                            + " if this is a big difference, be careful!"
                            + " You may need a quadratic!");
                        BrewServer.LOG.info("New: " + newMultiplier
                            + ". Old: " + volumeMultiplier);
                    }
                } else {
                    this.volumeMultiplier = newMultiplier;
                }

                if (volumeConstant.compareTo(BigDecimal.ZERO) != 0) {
                    if (newConstant.equals(volumeConstant)) {
                        BrewServer.LOG.info("The new constant "
                            + "isn't the same as the old one, if this is a big"
                            + " difference, be careful!"
                            + " You may need a quadratic!");
                        BrewServer.LOG.info("New: " + newConstant
                            + ". Old: " + volumeConstant);
                    }
                } else {
                    this.volumeConstant = newConstant;
                }
            }
            prevPair = pairs;
        }

        // we should be done now
    }


    /**
     * @return The latest volume reading
     */
    public BigDecimal updateVolume() {
        try {
            BigDecimal pinValue;
            if (volumeAIN != -1) {
                pinValue = new BigDecimal(volumePin.readValue());
            } else if (volumeAddress != null && volumeOffset != null) {
                try {
                    pinValue = new BigDecimal(
                        LaunchControl.readOWFSPath(
                            volumeAddress + "/volt." + volumeOffset));
                    if (this.stopVolumeLogging) {
                        BrewServer.LOG.log(Level.SEVERE,
                            "Recovered volume level reading for " + this.name);
                        this.stopVolumeLogging = false;
                    }
                } catch (Exception e) {
                    if (!this.stopVolumeLogging) {
                        BrewServer.LOG.log(Level.SEVERE,
                            "Could not update the volume reading from OWFS");
                        this.stopVolumeLogging = true;
                    }
                    BrewServer.LOG.info("Reconnecting OWFS");
                    LaunchControl.setupOWFS();

                    return BigDecimal.ZERO;
                }

            } else {
                return BigDecimal.ZERO;
            }

            // Are we outside of the known range?
            BigDecimal curKey = null, prevKey = null;
            BigDecimal curValue = null, prevValue = null;
            SortedSet<BigDecimal> keys = null;
            try {
                keys = Collections.synchronizedSortedSet(
                    new TreeSet<>(volumeBase.keySet()));
            } catch (NullPointerException npe) {
                // No VolumeBase setup, so we're probably calibrating
                return pinValue;
            } catch (Exception e) {
                BrewServer.LOG.log(Level.SEVERE,
                    "Uncaught exception when creating the volume set", e);
                System.exit(-1);
            }

            BigDecimal tVolume = null;

            try  {
                for (BigDecimal key: keys) {
                    if (prevKey == null) {
                        prevKey = key;
                        prevValue = volumeBase.get(key);
                        continue;
                    } else if (curKey != null) {
                        prevKey = curKey;
                        prevValue = curValue;
                    }

                    curKey = key;
                    curValue = volumeBase.get(key);

                    if (pinValue.compareTo(prevValue) >= 0
                            && pinValue.compareTo(curValue) <= 0) {
                        // We have a set encompassing the values!
                        // assume it's linear
                        BigDecimal volRange = curKey.subtract(prevKey);
                        BigDecimal readingRange = curValue.subtract(prevValue);
                        BigDecimal ratio = MathUtil.divide(pinValue.subtract(prevValue),readingRange);
                        BigDecimal volDiff = ratio.multiply(volRange);
                        tVolume = volDiff.add(prevKey);
                    }

                }

                if (tVolume == null && curKey != null) {
                    // Try to extrapolate
                    BigDecimal volRange = curKey.subtract(prevKey);
                    BigDecimal readingRange = curValue.subtract(prevValue);
                    BigDecimal ratio = MathUtil.divide(pinValue.subtract(prevValue),readingRange);
                    BigDecimal volDiff = ratio.multiply(volRange);
                    tVolume = volDiff.add(prevKey);
                }

            } catch (NoSuchElementException e) {
                // no more elements
                BrewServer.LOG.info("Finished reading Volume Elements");
            }

            if (tVolume == null) {
                // try to assume the value
                this.currentVolume = pinValue.subtract(volumeConstant)
                        .multiply(volumeMultiplier);
            } else {
                this.currentVolume = tVolume;
            }

            this.currentVolume = this.currentVolume.multiply(this.gravity);

            return pinValue;
        } catch (RuntimeException | IOException e) {
            e.printStackTrace();
        }
        return BigDecimal.ZERO;
    }

    /**
     * @param unit Unit to set the volume units to.
     */
    @SuppressWarnings("unused")
    public void setVolumeUnit(final String unit) {
        this.volumeUnit = unit;
    }

    /**
     * Append a volume measurement to the current list of calibrated values.
     * @param volume Volume measurement to record.
     * @return True if added OK.
     */
    public boolean addVolumeMeasurement(final BigDecimal volume) {
        // record 10 readings and average it
        BigDecimal maxReads = BigDecimal.TEN;
        BigDecimal total = new BigDecimal(0);
        for (int i = 0; i < maxReads.intValue(); i++) {
            try {
                try {
                    if (this.volumePin != null) {
                        total = total.add(new BigDecimal(
                            this.volumePin.readValue()));
                    } else {
                        try {
                            total = total.add(new BigDecimal(
                                LaunchControl.readOWFSPath(
                                    volumeAddress + "/volt." + volumeOffset)));
                        } catch (OwfsException e) {
                            e.printStackTrace();
                        }
                    }
                } catch (RuntimeException re) {
                    re.printStackTrace();
                    return false;
                } catch (IOException e) {
                    e.printStackTrace();
                    return false;
                }
            } catch (NumberFormatException  e) {
                BrewServer.LOG.warning("Bad Analog input value!");
                return false;
            }

            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                e.printStackTrace();

            }

        }

        // read in ten values
        BigDecimal avgValue = MathUtil.divide(total, maxReads);
        BrewServer.LOG.info("Read " + avgValue + " for "
                + volume + " " + volumeUnit);

        this.addVolumeMeasurement(volume, avgValue);
        System.out.println(this.name + ": Added volume data point " + volume);
        return true;
    }

    /**
     * Add a volume measurement at a specific key.
     * @param key The key to overwrite/set
     * @param value The value to overwrite/set
     */
    public void addVolumeMeasurement(
            final BigDecimal key, final BigDecimal value) {
        BrewServer.LOG.info("Adding " + key + " with value " + value);
        if (volumeBase == null) {
            this.volumeBase = new ConcurrentHashMap<>();
        }
        this.volumeBase.put(key, value);
    }

    /**
     * @return The current volume measurement,
     *       -1.0 is there is no measurement enabled.
     */
    public BigDecimal getVolume() {
        if (this.volumeMeasurement) {
            return this.currentVolume;
        }

        return BigDecimal.ONE.negate();
    }

    /**
     * @return The current volume unit
     */
    public String getVolumeUnit() {
        return this.volumeUnit;
    }

    /**
     * @return The analogue input
     */
    public String getVolumeAIN() {
        if (this.volumeAIN == -1) {
            return "";
        }
        return Integer.toString(this.volumeAIN);
    }

    /**
     * @return Get the volume address
     */
    public String getVolumeAddress() {
        return this.volumeAddress;
    }

    /**
     * @return The current volume offset
     */
    public String getVolumeOffset() {
        return this.volumeOffset;
    }

    /**
     * @return The current volume base map
     */
    public ConcurrentHashMap<BigDecimal, BigDecimal> getVolumeBase() {
        return this.volumeBase;
    }

    /**
     * Check to see if this object has a valid volume input.
     * @return true if there's a valid volume input on this class
     */
    public boolean hasVolume() {
        return (this.volumeAddress != null && !this.volumeAddress.equals("")
                && this.volumeOffset != null && !this.volumeOffset.equals("")) || (this.volumeAIN != -1);
    }

    /*****
     * Helper function to return a map of the current status.
     * @return The current status of the temperature probe.
     */
    public Map<String, Object> getMapStatus() {
        Map<String, Object> statusMap = new HashMap<>();
        statusMap.put("hidden", isHidden());
        statusMap.put("temp", getTemp());
        statusMap.put("elapsed", getTime());
        statusMap.put("scale", getScale());
        statusMap.put("cutoff", getCutoff());
        statusMap.put("cutoffEnabled", cutoffEnabled);
        statusMap.put("calibration", getCalibration());
        statusMap.put("gravity", gravity);
        statusMap.put("position", this.position);
        statusMap.put("size", this.size);

        if (currentError != null) {
            statusMap.put("error", currentError);
        }

        return statusMap;
    }

    public void shutdown() {
        // Graceful shutdown.
        keepalive = false;
        BrewServer.LOG.warning(this.getName() + " is shutting down");
        Thread.currentThread().interrupt();
    }

    public void setCalibration(String calibration) {
        // Lock the calibration temp
        calibration = calibration.replace(",", ".");
        Matcher tempMatcher = tempRegexp.matcher(calibration);

        if (tempMatcher.find()) {
            // We have matched against the TEMP_REGEXP
            String number = tempMatcher.group(1);
            if (number == null) {
                number = "+";
            }
            // Get the integer
            if (tempMatcher.group(2) != null) {
                number += tempMatcher.group(2);
            }
            // Do we have a decimal?
            if (tempMatcher.group(3) != null) {
                number += tempMatcher.group(3);
            }
            // Create the temp
            BigDecimal temperature = new BigDecimal(number);
            this.calibration = temperature.setScale(2, BigDecimal.ROUND_DOWN);
        } else {
            BrewServer.LOG.severe(calibration + " doesn't match "
                    + tempRegexp.pattern());
        }
    }

    public String getCalibration() {
        DecimalFormat df = new DecimalFormat("#.##");
        return df.format(this.calibration);
    }

    public boolean isSetup() {
        return !this.getName().equals(this.getProbe());
    }

    public void toggleVisibility() {
        if (this.hidden) {
            this.show();
        } else {
            this.hide();
        }
    }

    public void hide() {
        this.hidden = true;
    }

    public void show() {
        this.hidden = false;
    }

    public boolean isHidden() {
        return this.hidden;
    }

    public void setGravity(BigDecimal newGravity) {
        this.gravity = newGravity;
    }

    public BigDecimal getGravity() {
        return this.gravity;
    }

    @Override
    public String toString() {
        return this.getName();
    }

    /**
     * Get the current TriggerControl object, create a new one if it doesn't
     * exist.
     * @return the TriggerControl.
     */
    public TriggerControl getTriggerControl() {
        if (this.triggerControl == null) {
            this.triggerControl = new TriggerControl();
            this.triggerControl.setOutputControl(this.getName());
        }
        return triggerControl;
    }

    /**
     * @return The position of this temp probe in the list.
     */
    public int getPosition() {
        return this.position;
    }

    /**
     * Set the position of this temp probe.
     * @param newPos The new position.
     */
    public void setPosition(final int newPos) {
        this.position = newPos;
    }

    @Override
    public int compareTo(@Nonnull final Temp o) {
        if (o.getPosition() == this.position) {
            return o.getName().compareTo(this.name);
        }
        if (this.position == -1) {
            return 1;
        }
        return this.position - o.getPosition();
    }

    public BigDecimal convertF(BigDecimal temp) {
        if (this.scale.equals("C")) {
            return cToF(temp);
        }

        return temp;
    }

    /**
     * Get the current Size.
     * @return {@value #SIZE_SMALL} {@value #SIZE_MEDIUM} or {@value #SIZE_LARGE}
     */
    public int getSize() {
        return size;
    }

    /**
     * Set the new size of this render.
     * @param newSize {@value #SIZE_SMALL} {@value #SIZE_MEDIUM} or {@value #SIZE_LARGE}
     */
    public void setSize(int newSize) {
        if (size == -1) {
            return;
        }
        size = newSize;
    }

    public String getI2CDevNumberString() {
        if (i2cDevice == null)
        {
            return "";
        }
        return i2cDevice.getDevNumberString();
    }

    public String getI2CDevAddressString() {
        if (i2cDevice == null)
        {
            return "";
        }
        return Integer.toString(i2cDevice.getAddress());
    }

    public String geti2cChannel() {
        if (i2cChannel == -1)
        {
            return "";
        }
        return Integer.toString(i2cChannel);
    }

    public String getI2CDevType() {
        if (i2cDevice == null)
        {
            return "";
        }
        return i2cDevice.getDevName();
    }

    public boolean setupVolumeI2C(String i2c_device, String i2c_address, String i2c_channel, String i2c_type, String units) {
        return setupVolumeI2C(LaunchControl.getI2CDevice(i2c_device, i2c_address, i2c_type), i2c_channel, units);
    }

    public boolean setupVolumeI2C(I2CDevice i2c_device, String i2c_channel, String volumeUnits) {
        this.i2cDevice = i2c_device;
        this.i2cChannel = Integer.parseInt(i2c_channel);
        this.setVolumeUnit(volumeUnits);
        return (i2cDevice != null);
    }
}
