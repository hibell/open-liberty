/*******************************************************************************
 * Copyright (c) 2018 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.kernel.service.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.math.BigDecimal;
import java.math.RoundingMode;

import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import com.ibm.websphere.ras.Tr;
import com.ibm.websphere.ras.TraceComponent;
import com.ibm.ws.ffdc.FFDCFilter;

/**
 * API for getting cpu info about the system
 */
public class CpuInfo {

    /**
     * Trace component for this class.
     */
    private final static TraceComponent tc = Tr.register(CpuInfo.class);

    private static CpuInfo instance;
    private final int AVAILABLE_PROCESSORS;

    // For CPU usage calculation
    private final OperatingSystemMXBean osmx;
    private final MBeanServer mBeanServer;
    private ObjectName operatingSystemMbean;
    final private int cpuNSFactor;
    private long lastProcessCPUTime = 0;
    private double lastProcessCpuUsage = -1;
    private long lastSystemTimeMillis = -1;

    private CpuInfo() {
        // find available processors
        int runtimeAvailableProcessors = Runtime.getRuntime().availableProcessors();
        int fileSystemAvailableProcessors = getAvailableProcessorsFromFilesystem();

        if (fileSystemAvailableProcessors <= 0 || fileSystemAvailableProcessors > runtimeAvailableProcessors) {
            AVAILABLE_PROCESSORS = runtimeAvailableProcessors;
        } else {
            AVAILABLE_PROCESSORS = fileSystemAvailableProcessors;
        }

        // initialize mbean required to get CPU usage info
        osmx = ManagementFactory.getOperatingSystemMXBean();
        mBeanServer = ManagementFactory.getPlatformMBeanServer();

        try {
            operatingSystemMbean = new ObjectName("java.lang", "type", "OperatingSystem");
        } catch (MalformedObjectNameException e) {
            FFDCFilter.processException(e, getClass().getName(), "CpuInfo<init>");
        }

        int nsFactor = 1;
        // adjust for J9 cpuUsage units change from hundred-nanoseconds to nanoseconds in Java8sr5
        if (JavaInfo.vendor() == JavaInfo.Vendor.IBM) {
            int majorVersion = JavaInfo.majorVersion();
            int minorVersion = JavaInfo.minorVersion();
            int serviceRelease = JavaInfo.serviceRelease();
            if (majorVersion <= 7) {
                nsFactor = 100;
            } else if (majorVersion == 8 && minorVersion == 0 && serviceRelease < 5) {
                nsFactor = 100;
            }

            if (tc.isEventEnabled()) {
                Tr.event(tc, "IBM Java level check", ("majorVersion: " + majorVersion + ", minorVersion: " + minorVersion +
                                                      ", serviceRelease: " + serviceRelease + ", cpuNSFactor: " + nsFactor));
            }
        }
        cpuNSFactor = nsFactor;
    }

    private double getSystemCPU() {
        final String OS_ATTRIBUTE_SYSTEM_CPU_LOAD = "SystemCpuLoad";
        double cpuUsage = -1;

        // Get the system cpu usage
        try {
            if (osmx != null) {
                cpuUsage = (Double) mBeanServer.getAttribute(operatingSystemMbean, OS_ATTRIBUTE_SYSTEM_CPU_LOAD);
            }
        } catch (Exception e) {
            FFDCFilter.processException(e, getClass().getName(), "getSystemCPU");
        }
        // if we get back a negative value, it means cpuUsage is not available
        // otherwise, normalize to present as percentage
        if (cpuUsage >= 0) {
            cpuUsage *= 100;
            if (cpuUsage > 100) {
                if (tc.isEventEnabled()) {
                    Tr.event(tc, "getSystemCPU error", ("system CPU out-of-range: " + cpuUsage));
                }
                cpuUsage = -1;
            }
        }

        return cpuUsage;
    }

    private synchronized double getProcessCPU() {
        final String OS_ATTRIBUTE_PROCESS_CPU_TIME = "ProcessCpuTime";

        // update process cpu usage at most once every 500 ms
        long currentTimeMs = System.currentTimeMillis();
        if (currentTimeMs - lastSystemTimeMillis < 500)
            return lastProcessCpuUsage;

        double cpuUsage = -1;
        long processCpuTime = -1;
        // Get the CPU time from the mbean
        try {
            // There should already be an FFDC logged if there was an issue getting a reference to the operatingSystemMbean.
            if (osmx != null) {
                processCpuTime = (Long) mBeanServer.getAttribute(operatingSystemMbean, OS_ATTRIBUTE_PROCESS_CPU_TIME);
            }
        } catch (Exception e) {
            FFDCFilter.processException(e, getClass().getName(), "getProcessCPU");
        }

        if (processCpuTime != -1) {
            // processCpuTime is in nanos, so need to convert from millis
            long d1 = (currentTimeMs - lastSystemTimeMillis) * 1000000;
            long d2 = processCpuTime - lastProcessCPUTime;
            cpuUsage = (double) d2 / d1;
            cpuUsage = (cpuUsage / AVAILABLE_PROCESSORS) * cpuNSFactor * 100;

            lastSystemTimeMillis = currentTimeMs;
            lastProcessCPUTime = processCpuTime;
        }

        if (cpuUsage > 100) {
            if (tc.isEventEnabled()) {
                Tr.event(tc, "getProcessCPU error", ("process CPU out-of-range: " + cpuUsage));
            }
            cpuUsage = -1;
        }
        lastProcessCpuUsage = cpuUsage;
        return cpuUsage;
    }

    // utility below parses cpu limits info from Docker files
    private static int getAvailableProcessorsFromFilesystem() {
        boolean isTraceOn = TraceComponent.isAnyTracingEnabled();

        Double availableProcessorsDouble = null;
        int availableProcessorsInt = -1;

        //Check for docker files
        String periodFileLocation = File.separator + "sys" + File.separator + "fs" + File.separator + "cgroup" + File.separator + "cpu" + File.separator + "cpu.cfs_period_us";
        String quotaFileLocation = File.separator + "sys" + File.separator + "fs" + File.separator + "cgroup" + File.separator + "cpu" + File.separator + "cpu.cfs_quota_us";
        File cfsPeriod = new File(periodFileLocation);
        File cfsQuota = new File(quotaFileLocation);
        if (cfsPeriod.exists() && cfsQuota.exists()) { //Found docker files
            //Read quota
            try {
                String quotaContents = readFile(cfsQuota);
                double quotaFloat = new Double(quotaContents);
                if (isTraceOn && tc.isDebugEnabled())
                    Tr.debug(tc, "quotaFloat = " + quotaFloat);
                if (quotaFloat >= 0) {
                    //Read period
                    String periodContents = readFile(cfsPeriod);
                    double periodFloat = new Double(periodContents);
                    if (isTraceOn && tc.isDebugEnabled())
                        Tr.debug(tc, "periodFloat = " + periodFloat);
                    if (periodFloat != 0) {
                        availableProcessorsDouble = quotaFloat / periodFloat;
                        availableProcessorsDouble = roundToTwoDecimalPlaces(availableProcessorsDouble);
                        if (isTraceOn && tc.isDebugEnabled())
                            Tr.debug(tc, "Calculated availableProcessors: " + availableProcessorsDouble + ". period=" + periodFloat + ", quota=" + quotaFloat);
                    }
                }
            } catch (Throwable e) {
                if (isTraceOn && tc.isDebugEnabled())
                    Tr.debug(tc, "Caught exception: " + e.getMessage() + ". Using number of processors reported by java");
                availableProcessorsDouble = null;
            }
        } else {
            if (isTraceOn && tc.isDebugEnabled()) {
                Tr.debug(tc, "Files " + quotaFileLocation + " : " + cfsQuota.exists());
                Tr.debug(tc, "Files " + periodFileLocation + " : " + cfsPeriod.exists());
            }
        }

        availableProcessorsInt = (availableProcessorsDouble == null) ? -1 : availableProcessorsDouble.intValue();

        // make sure any z.xy cpu quota was not rounded down (especially to 0 ...) during int conversion
        if (availableProcessorsDouble != null && availableProcessorsDouble > availableProcessorsInt)
            availableProcessorsInt++;

        return availableProcessorsInt;
    }

    private static String readFile(File file) throws IOException {
        InputStream is = new FileInputStream(file);
        BufferedReader buf = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        String line = buf.readLine();
        StringBuilder sb = new StringBuilder();
        while (line != null) {
            sb.append(line).append("\n");
            line = buf.readLine();
        }

        buf.close();
        is.close();
        return sb.toString();
    }

    private static Double roundToTwoDecimalPlaces(Double d) {
        BigDecimal bd = new BigDecimal(d);
        bd = bd.setScale(2, RoundingMode.DOWN);
        return bd.doubleValue();
    }

    private static CpuInfo instance() {
        if (instance == null)
            instance = new CpuInfo();
        return instance;
    }

    /**
     * Returns the number of hardware threads (aka cpus) available to this Java process
     *
     * @return int available processors
     */
    public static int getAvailableProcessors() {
        return instance().AVAILABLE_PROCESSORS;
    }

    /**
     * Returns the cpu usage by this Java process in the last interval
     *
     * @return double process cpu usage (returns -1 if info not available)
     */
    public static double getJavaCpuUsage() {
        return instance().getProcessCPU();
    }

    /**
     * Returns the cpu usage on the system (all cpus, all processes) in the last interval
     *
     * @return double system cpu usage (returns -1 if info not available)
     */
    public static double getSystemCpuUsage() {
        return instance().getSystemCPU();
    }
}
