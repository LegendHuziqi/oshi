/**
 * Oshi (https://github.com/oshi/oshi)
 *
 * Copyright (c) 2010 - 2018 The Oshi Project Team
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Maintainers:
 * dblock[at]dblock[dot]org
 * widdis[at]gmail[dot]com
 * enrico.bianchi[at]gmail[dot]com
 *
 * Contributors:
 * https://github.com/oshi/oshi/graphs/contributors
 */
package oshi.jna.platform.windows;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.TimeoutException;

import org.junit.Test;

import com.sun.jna.platform.win32.Variant;

import oshi.PlatformEnum;
import oshi.SystemInfo;
import oshi.jna.platform.windows.Wbemcli;
import oshi.jna.platform.windows.Wbemcli.WbemcliException;
import oshi.jna.platform.windows.WbemcliUtil;
import oshi.jna.platform.windows.WbemcliUtil.WmiQuery;
import oshi.jna.platform.windows.WbemcliUtil.WmiResult;

/**
 * Test class for Wbemcli and WbemcliUti methods and classes used to query WMI.
 * Also tests some methods of Ole32.
 */
public class WbemcliTest {

    /**
     * Properties to retrieve from Win32_Process
     */
    enum ProcessProperty {
        PROCESSID, // UINT32
        WORKINGSETSIZE, // UINT64
        CREATIONDATE, // DATETIME
        EXECUTIONSTATE, // Always NULL
        COMMANDLINE; // STRING
    }

    /**
     * Properties to retrieve from Win32_OperatingSystem
     */
    enum OperatingSystemProperty {
        FOREGROUNDAPPLICATIONBOOST, // UINT8
        OSTYPE, // UINT16
        PRIMARY; // BOOLEAN
    }

    @Test
    public void testWmiExceptions() {
        if (!SystemInfo.getCurrentPlatformEnum().equals(PlatformEnum.WINDOWS)) {
            return;
        }
        WmiQuery<ProcessProperty> processQuery = WbemcliUtil.createQuery("Win32_Process", ProcessProperty.class);
        try {
            // This query should take more than 0 ms
            WbemcliUtil.queryWMI(processQuery, 0);
            // Highly unlikely to get this far, but if we do, no failure
            System.err.println("Warning: Win32_Process WMI query returned in 0 ms. This is unusual.");
        } catch (TimeoutException expected) {
            assertEquals("No results after 0 ms.", expected.getMessage());
        }

        // Invalid class
        processQuery.setWmiClassName("Win32_ClassDoesNotExist");
        try {
            WbemcliUtil.queryWMI(processQuery);
            fail("Win32_ClassDoesNotExist does not exist.");
        } catch (WbemcliException expected) {
            assertEquals(Wbemcli.WBEM_E_INVALID_CLASS, expected.getErrorCode());
        }

        // Valid class but properties don't match the class
        processQuery.setWmiClassName("Win32_OperatingSystem");
        try {
            WbemcliUtil.queryWMI(processQuery);
            fail("Properties in the process enum aren't in Win32_OperatingSystem");
        } catch (WbemcliException expected) {
            assertEquals(Wbemcli.WBEM_E_INVALID_QUERY, expected.getErrorCode());
        }

        // Invalid namespace
        processQuery.setNameSpace("Invalid");
        try {
            WbemcliUtil.queryWMI(processQuery);
            fail("This is an invalid namespace.");
        } catch (WbemcliException expected) {
            assertEquals(Wbemcli.WBEM_E_INVALID_NAMESPACE, expected.getErrorCode());
        }
    }

    @Test
    public void testNamespace() {
        if (!SystemInfo.getCurrentPlatformEnum().equals(PlatformEnum.WINDOWS)) {
            return;
        }
        assertTrue(WbemcliUtil.hasNamespace(WbemcliUtil.DEFAULT_NAMESPACE));
        assertFalse(WbemcliUtil.hasNamespace("Name Space"));
    }

    @Test
    public void testWmiProcesses() {
        if (!SystemInfo.getCurrentPlatformEnum().equals(PlatformEnum.WINDOWS)) {
            return;
        }
        WmiQuery<ProcessProperty> processQuery = WbemcliUtil.createQuery("Win32_Process", ProcessProperty.class);

        WmiResult<ProcessProperty> processes = WbemcliUtil.queryWMI(processQuery);
        // There has to be at least one process (this one!)
        assertTrue(processes.getResultCount() > 0);
        int lastProcessIndex = processes.getResultCount() - 1;

        // PID is UINT32 = VT_I4, should only work with getInteger
        assertTrue(processes.getInteger(ProcessProperty.PROCESSID, lastProcessIndex) >= 0);
        // Other types should throw exception with error code VT_I4 = 3
        try {
            processes.getString(ProcessProperty.PROCESSID, lastProcessIndex);
            fail("VT_I4 type cannot be returned as a String");
        } catch (WbemcliException expected) {
            assertEquals(Variant.VT_I4, expected.getErrorCode());
        }

        // WSS is UINT64 = STRING, should only work with getString and parse to
        // a long
        String wssStr = processes.getString(ProcessProperty.WORKINGSETSIZE, lastProcessIndex);
        assertTrue(Long.parseLong(wssStr) > 0);
        // Other types should throw exception with error code VT_BSTR = 8
        try {
            processes.getInteger(ProcessProperty.WORKINGSETSIZE, lastProcessIndex);
            fail("VT_BSTR type cannot be returned as an Integer");
        } catch (WbemcliException expected) {
            assertEquals(Variant.VT_BSTR, expected.getErrorCode());
        }

        // EXECUTIONSTATE is always null, should only work with getValue
        Object state = processes.getValue(ProcessProperty.EXECUTIONSTATE, lastProcessIndex);
        assertNull(state);
        // Other types should return defaults
        assertEquals("", processes.getString(ProcessProperty.EXECUTIONSTATE, lastProcessIndex));
        assertEquals((Integer) 0, processes.getInteger(ProcessProperty.EXECUTIONSTATE, lastProcessIndex));
        assertEquals((Short) (short) 0, processes.getShort(ProcessProperty.EXECUTIONSTATE, lastProcessIndex));
        assertEquals((Byte) (byte) 0, processes.getByte(ProcessProperty.EXECUTIONSTATE, lastProcessIndex));
        assertEquals((Float) 0f, processes.getFloat(ProcessProperty.EXECUTIONSTATE, lastProcessIndex));
        assertEquals((Double) 0d, processes.getDouble(ProcessProperty.EXECUTIONSTATE, lastProcessIndex));
        assertEquals(Boolean.FALSE, processes.getBoolean(ProcessProperty.EXECUTIONSTATE, lastProcessIndex));

        // CreationDate is DATETIME = STRING, should only work with getString
        // and be in CIM_DATETIME format yyyymmddhhmmss.mmmmmm+zzz
        String cdate = processes.getString(ProcessProperty.CREATIONDATE, lastProcessIndex);

        assertEquals(25, cdate.length());
        assertEquals('.', cdate.charAt(14));
        assertTrue(Integer.parseInt(cdate.substring(0, 4)) > 1970);
        assertTrue(Integer.parseInt(cdate.substring(4, 6)) <= 12);
        assertTrue(Integer.parseInt(cdate.substring(6, 8)) <= 31);
        // Other types should throw exception with error code VT_BSTR = 8
        try {
            processes.getFloat(ProcessProperty.CREATIONDATE, lastProcessIndex);
            fail("VT_BSTR type cannot be returned as a Float");
        } catch (WbemcliException expected) {
            assertEquals(Variant.VT_BSTR, expected.getErrorCode());
        }
    }

    @Test
    public void testWmiOperatingSystem() {
        if (!SystemInfo.getCurrentPlatformEnum().equals(PlatformEnum.WINDOWS)) {
            return;
        }
        WmiQuery<OperatingSystemProperty> operatingSystemQuery = WbemcliUtil.createQuery("Win32_OperatingSystem",
                OperatingSystemProperty.class);

        WmiResult<OperatingSystemProperty> os = WbemcliUtil.queryWMI(operatingSystemQuery);
        // There has to be at least one os (this one!)
        assertTrue(os.getResultCount() > 0);

        // ForegroundApplicationBoost is UINT8 = VT_UI1, should only work with
        // getByte
        assertTrue(os.getByte(OperatingSystemProperty.FOREGROUNDAPPLICATIONBOOST, 0) >= 0);
        // Other types should throw exception with error code VT_UI1 = 17
        try {
            os.getInteger(OperatingSystemProperty.FOREGROUNDAPPLICATIONBOOST, 0);
            fail("VT_UI1 type cannot be returned as an Integer");
        } catch (WbemcliException expected) {
            assertEquals(Variant.VT_UI1, expected.getErrorCode());
        }

        // OSTYPE is UINT16 = VT_I4, should only work with getInteger
        assertTrue(os.getInteger(OperatingSystemProperty.OSTYPE, 0) >= 0);
        // Other types should throw exception with error code VT_I4 = 3
        try {
            os.getByte(OperatingSystemProperty.OSTYPE, 0);
            fail("VT_I4 type cannot be returned as a Byte");
        } catch (WbemcliException expected) {
            assertEquals(Variant.VT_I4, expected.getErrorCode());
        }

        // PRIMARY is BOOLEAN = VT_BOOL, should only work with getBoolean
        assertNotNull(os.getValue(OperatingSystemProperty.PRIMARY, 0));
        // Other types should throw exception with error code VT_BOOL = 11
        try {
            os.getDouble(OperatingSystemProperty.PRIMARY, 0);
            fail("VT_BOOL type cannot be returned as a Double");
        } catch (WbemcliException expected) {
            assertEquals(Variant.VT_BOOL, expected.getErrorCode());
        }
    }
}
