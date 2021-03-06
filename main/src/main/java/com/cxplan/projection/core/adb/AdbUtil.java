package com.cxplan.projection.core.adb;

import com.android.ddmlib.*;
import com.cxplan.projection.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Kenny
 * created on 2018/8/20
 */
public class AdbUtil {

    private static final Logger logger = LoggerFactory.getLogger(AdbUtil.class);

    public static String getDeviceId(IDevice device) {
        if (device == null) {
            return null;
        }

        String value = device.getProperty("ro.ytx.imei");
        if (value == null) {
            value = device.getProperty("ro.serialno");
            if (value == null) {
                try {
                    value = device.getSystemProperty("ro.serialno").get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return value == null ? device.getSerialNumber() : value;
    }

    /**
     * The 'ps' command in system is different in different android version.
     * The parameters are changed in 8 and greater version, adding '-A' to retrieve all process information.
     *
     * @return return valid ps command.
     */
    public static String getPsCommand(IDevice device) {
        String version = device.getProperty("ro.build.version.release");
        if (version == null) {
            return "ps";
        }
        version = version.trim();
        int versionValue;
        String majorVersion;
        int index = version.indexOf(".");
        if (index > -1) {
            majorVersion = version.substring(0, index);
        } else {
            majorVersion = version;
        }

        try {
            versionValue = Integer.parseInt(majorVersion);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return "ps";
        }

        if (versionValue >= 8) {
            return "ps -A";
        } else {
            return "ps";
        }
    }

    //Physical size: 720x1280
    public static Dimension getPhysicalSize(IDevice device) {
        String cmd = "wm size";
        String ret = shell(cmd, device);
        if (StringUtil.isEmpty(ret)) {
            return null;
        }

        int index = ret.indexOf(":");
        if (index == -1) {
            logger.error("The format of wm size is illegal: " + ret);
            return null;
        }
        ret = ret.substring(index + 1).trim();
        index = ret.indexOf("x");
        if (index == -1) {
            logger.error("The format of wm size is illegal: " + ret);
            return null;
        }

        int width = StringUtil.getIntValue(ret.substring(0, index), -1);
        int height = StringUtil.getIntValue(ret.substring(index + 1), -1);
        if (width == -1 || height == -1) {
            logger.error("The format of wm size is illegal: " + ret);
            return null;
        }

        return new Dimension(width, height);
    }

    /**
     * Retrieve the path of specified package.
     * @param packageName package name.
     * @param device device object.
     * @return the package path, null value indicates the package doesn't exist.
     */
    public static String getPackagePath(String packageName, IDevice device) {
        String cmd = "pm path " + packageName;
        String ret = shell(cmd, device);
        if (StringUtil.isEmpty(ret)) {//the package doesn't exist.
            return null;
        }
        ret = ret.trim();
        int index = ret.indexOf(":");
        if (index == -1) {
            throw new RuntimeException("The illegal package path format: " + ret);
        }

        return ret.substring(index + 1);
    }

    /**
     * Return the version code of specified package, -1 value will be returned
     * if the package doesn't exist.
     *
     * @param packageName the package name.
     * @param device device object.
     * @return the version code of package.
     */
    public static int getPackageVersionCode(String packageName, IDevice device) {
        String cmd = "dumpsys package " + packageName + "|grep versionCode";
        String ret = shell(cmd, device);
        if (StringUtil.isEmpty(ret)) {//the package doesn't exist.
            return -1;
        }
        ret = ret.trim();
        int index = ret.indexOf("=");
        if (index == -1) {
            throw new RuntimeException("The illegal package path format: " + ret);
        }

        int i = index + 1;
        int endIndex = -1;
        for (; i < ret.length(); i++) {
            if (!Character.isDigit(ret.charAt(i))) {
                endIndex = i;
                break;
            }
        }

        if (endIndex == -1) {
            throw new RuntimeException("The illegal package path format: " + ret);
        }

        String string = ret.substring(index + 1, endIndex);
        int code = StringUtil.getIntValue(string, -1);
        if (code == -1) {
            throw new RuntimeException("The illegal package path format: " + ret);
        }
        return code;
    }

    public static String shell(String cmd, IDevice device) {
        // 5000 is the default timeout from the ddmlib.
        // This timeout arg is needed to the backwards compatibility.
        return shell(cmd, 5000, device);
    }

    /**
     * Execute a shell command, and return the output string.
     * A timeout can be specified in millisecond.
     *
     * @param cmd the shell command
     * @param timeout the max time of executing command.
     * @param device device object.
     * @return the result of command.
     */
    public static String shell(String cmd, int timeout, IDevice device) {
        CommandOutputCapture capture = new CommandOutputCapture();
        try {
            device.executeShellCommand(cmd, capture, timeout, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        return capture.toString();
    }

    /**
     * Execute a shell command which will not be terminated except for forcing
     * to exit manually.
     * This method should be invoked in another thread usually, because the thread
     * is blocked util the vm process exits.
     *
     * @param cmd the shell command
     * @param device device object.
     * @throws AdbCommandRejectedException
     * @throws IOException
     * @throws TimeoutException
     */
    public static void shellBlockingCommand(String cmd, IDevice device) throws AdbCommandRejectedException, IOException, TimeoutException {
        shellBlockingCommand(cmd, device, 2);
    }
    public static void shellBlockingCommand(String cmd, IDevice device, int timeout) throws AdbCommandRejectedException, IOException, TimeoutException {
        try {
            device.executeShellCommand(cmd, new LoggingOutputReceiver(), timeout, TimeUnit.SECONDS);
        } catch (ShellCommandUnresponsiveException e) {
            logger.info("blocking cmd: {}", e.getMessage());
        }
    }

    /**
     * Kill specified process run on device.
     *
     * @param pid the id of process.
     * @param device device object.
     */
    public static void killProcess(int pid, IDevice device) {
        if (pid < 0) {
            return;
        }
        String cmd = "kill -9 " + pid;
        shell(cmd, device);
    }

    /**
     * Pulls a single file. A monitor can be specified to get progress in real time.
     *
     * @param remote the full path to the remote file
     * @param local The local destination.
     *
     */
    public static void pullFile(IDevice device, String remote, String local, SyncService.ISyncProgressMonitor monitor) {
        try {
            device.getSyncService().pullFile(remote, local, monitor );
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    /**
     * Return whether specified device is connected with pc by wireless network.
     * @param device the device object.
     * @return true: wireless, false: usb cable.
     */
    public static boolean isWirelessDevice(IDevice device) {
        String featureName = device.getSerialNumber();
        if (featureName != null && featureName.endsWith(":5555")) {
            return true;
        } else {
            return false;
        }
    }

    private static Pattern ipPattern = Pattern.compile("inet addr:(.*?) ");

    /**
     * Retrieve the ip address of device by adb shell.
     * @param device device channel.
     * @return the ip address of device, a null value will be returned if device doesn't use wifi network.
     */
    public static String getDeviceIp(IDevice device) {
        String cmd = "ifconfig wlan0";
        String ret = shell(cmd, device);
        if (StringUtil.isEmpty(ret)) {//the package doesn't exist.
            return null;
        }

        Matcher matcher = ipPattern.matcher(ret);
        if (matcher.find()) {
            String ip = matcher.group(1);
            return ip;
        } else {
            return null;
        }
    }

    public static class LoggingOutputReceiver implements IShellOutputReceiver {

        public LoggingOutputReceiver() {
        }

        public void addOutput(byte[] data, int offset, int length) {
            String message = new String(data, offset, length);
            String[] arr = message.split("\n");
            for (String s : arr) {
                logger.info("--->" + s);
            }
        }

        public void flush() {
        }

        public boolean isCancelled() {
            return false;
        }
    }

}
