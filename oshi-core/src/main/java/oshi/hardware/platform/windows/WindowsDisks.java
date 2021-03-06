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
package oshi.hardware.platform.windows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jna.platform.win32.Kernel32; // NOSONAR squid:S1191

import oshi.hardware.Disks;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HWPartition;
import oshi.jna.platform.windows.PdhUtil;
import oshi.jna.platform.windows.PdhUtil.PdhEnumObjectItems;
import oshi.jna.platform.windows.PdhUtil.PdhException;
import oshi.jna.platform.windows.WbemcliUtil;
import oshi.jna.platform.windows.WbemcliUtil.WmiQuery;
import oshi.jna.platform.windows.WbemcliUtil.WmiResult;
import oshi.util.MapUtil;
import oshi.util.ParseUtil;
import oshi.util.platform.windows.PerfDataUtil;
import oshi.util.platform.windows.WmiUtil;

/**
 * Windows hard disk implementation.
 *
 * @author enrico[dot]bianchi[at]gmail[dot]com
 */
public class WindowsDisks implements Disks {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(WindowsDisks.class);

    /**
     * Maps to store read/write bytes per drive index
     */
    private static Map<String, Long> readMap = new HashMap<>();
    private static Map<String, Long> readByteMap = new HashMap<>();
    private static Map<String, Long> writeMap = new HashMap<>();
    private static Map<String, Long> writeByteMap = new HashMap<>();
    private static Map<String, Long> xferTimeMap = new HashMap<>();
    private static Map<String, Long> timeStampMap = new HashMap<>();
    private static Map<String, List<String>> driveToPartitionMap = new HashMap<>();
    private static Map<String, String> partitionToLogicalDriveMap = new HashMap<>();
    private static Map<String, HWPartition> partitionMap = new HashMap<>();

    private static final Pattern DEVICE_ID = Pattern.compile(".*\\.DeviceID=\"(.*)\"");

    private static final int BUFSIZE = 255;

    private static final String physicalDiskPerfObject = PdhUtil.PdhLookupPerfNameByIndex(null,
            PdhUtil.PdhLookupPerfIndexByEnglishName("PhysicalDisk"));

    enum DiskDriveProperty {
        INDEX, MANUFACTURER, MODEL, NAME, SERIALNUMBER, SIZE;
    }

    private static final WmiQuery<DiskDriveProperty> DISK_DRIVE_QUERY = WbemcliUtil.createQuery("Win32_DiskDrive",
            DiskDriveProperty.class);

    enum DriveToPartitionProperty {
        ANTECEDENT, DEPENDENT;
    }

    private static final WmiQuery<DriveToPartitionProperty> DRIVE_TO_PARTITION_QUERY = WbemcliUtil
            .createQuery("Win32_DiskDriveToDiskPartition", DriveToPartitionProperty.class);
    private static final WmiQuery<DriveToPartitionProperty> DISK_TO_PARTITION_QUERY = WbemcliUtil
            .createQuery("Win32_LogicalDiskToPartition", DriveToPartitionProperty.class);

    enum DiskPartitionProperty {
        DESCRIPTION, DEVICEID, DISKINDEX, INDEX, NAME, SIZE, TYPE;
    }

    private static final WmiQuery<DiskPartitionProperty> PARTITION_QUERY = WbemcliUtil
            .createQuery("Win32_DiskPartition", DiskPartitionProperty.class);

    private static final String PDH_DISK_READS_FORMAT = "\\PhysicalDisk(%s)\\Disk Reads/sec";
    private static final String PDH_DISK_READ_BYTES_FORMAT = "\\PhysicalDisk(%s)\\Disk Read Bytes/sec";
    private static final String PDH_DISK_WRITES_FORMAT = "\\PhysicalDisk(%s)\\Disk Writes/sec";
    private static final String PDH_DISK_WRITE_BYTES_FORMAT = "\\PhysicalDisk(%s)\\Disk Write Bytes/sec";
    private static final String PDH_DISK_TIME_FORMAT = "\\PhysicalDisk(%s)\\%% Disk Time";

    private static final String PHYSICALDRIVE_PREFIX = "\\\\.\\PHYSICALDRIVE";

    public static boolean updateDiskStats(HWDiskStore diskStore) {
        String index = null;
        HWPartition[] partitions = diskStore.getPartitions();
        if (partitions.length > 0) {
            // If a partition exists on this drive, the major property
            // corresponds to the disk index, so use it.
            index = Integer.toString(partitions[0].getMajor());
        } else if (diskStore.getName().startsWith(PHYSICALDRIVE_PREFIX)) {
            // If no partition exists, Windows reliably uses a name to match the
            // disk index. That said, the skeptical person might wonder why a
            // disk has read/write statistics without a partition, and wonder
            // why this branch is even relevant as an option. The author of this
            // comment does not have an answer for this valid question.
            index = diskStore.getName().substring(PHYSICALDRIVE_PREFIX.length(), diskStore.getName().length());
        } else {
            // The author of this comment cannot fathom a circumstance in which
            // the code reaches this point, but just in case it does, here's the
            // correct response. If you get this log warning, the circumstances
            // would be of great interest to the project's maintainers.
            LOG.warn("Couldn't match index for {}", diskStore.getName());
            return false;
        }
        populateReadWriteMaps(index);
        if (readMap.containsKey(index)) {
            diskStore.setReads(MapUtil.getOrDefault(readMap, index, 0L));
            diskStore.setReadBytes(MapUtil.getOrDefault(readByteMap, index, 0L));
            diskStore.setWrites(MapUtil.getOrDefault(writeMap, index, 0L));
            diskStore.setWriteBytes(MapUtil.getOrDefault(writeByteMap, index, 0L));
            diskStore.setTransferTime(MapUtil.getOrDefault(xferTimeMap, index, 0L));
            diskStore.setTimeStamp(MapUtil.getOrDefault(timeStampMap, index, 0L));
            return true;
        } else {
            return false;
        }

    }

    @Override
    public HWDiskStore[] getDisks() {
        List<HWDiskStore> result;
        result = new ArrayList<>();
        populateReadWriteMaps(null);
        populatePartitionMaps();

        WmiResult<DiskDriveProperty> vals = WmiUtil.queryWMI(DISK_DRIVE_QUERY);

        for (int i = 0; i < vals.getResultCount(); i++) {
            HWDiskStore ds = new HWDiskStore();
            ds.setName(vals.getString(DiskDriveProperty.NAME, i));
            ds.setModel(String.format("%s %s", vals.getString(DiskDriveProperty.MODEL, i),
                    vals.getString(DiskDriveProperty.MANUFACTURER, i)).trim());
            // Most vendors store serial # as a hex string; convert
            ds.setSerial(ParseUtil.hexStringToString(vals.getString(DiskDriveProperty.SERIALNUMBER, i)));
            String index = vals.getInteger(DiskDriveProperty.INDEX, i).toString();
            ds.setReads(MapUtil.getOrDefault(readMap, index, 0L));
            ds.setReadBytes(MapUtil.getOrDefault(readByteMap, index, 0L));
            ds.setWrites(MapUtil.getOrDefault(writeMap, index, 0L));
            ds.setWriteBytes(MapUtil.getOrDefault(writeByteMap, index, 0L));
            ds.setTransferTime(MapUtil.getOrDefault(xferTimeMap, index, 0L));
            ds.setTimeStamp(MapUtil.getOrDefault(timeStampMap, index, 0L));
            ds.setSize(ParseUtil.parseLongOrDefault(vals.getString(DiskDriveProperty.SIZE, i), 0L));
            // Get partitions
            List<HWPartition> partitions = new ArrayList<>();
            List<String> partList = driveToPartitionMap.get(ds.getName());
            if (partList != null && !partList.isEmpty()) {
                for (String part : partList) {
                    if (partitionMap.containsKey(part)) {
                        partitions.add(partitionMap.get(part));
                    }
                }
            }
            ds.setPartitions(partitions.toArray(new HWPartition[partitions.size()]));
            // Add to list
            result.add(ds);
        }
        return result.toArray(new HWDiskStore[result.size()]);
    }

    /**
     * Populates the maps for the specified index. If the index is null,
     * populates all the maps
     * 
     * @param index
     *            The index to populate/update maps for
     */
    private static void populateReadWriteMaps(String index) {
        // If index is null, start from scratch.
        if (index == null) {
            readMap.clear();
            readByteMap.clear();
            writeMap.clear();
            writeByteMap.clear();
            xferTimeMap.clear();
            timeStampMap.clear();
        }
        // Fetch the instance names
        PdhEnumObjectItems objectItems;
        try {
            objectItems = PdhUtil.PdhEnumObjectItems(null, null, physicalDiskPerfObject, 100);
        } catch (PdhException e) {
            LOG.error("Unable to enumerate instances for {}.", physicalDiskPerfObject);
            return;
        }
        List<String> instances = objectItems.getInstances();
        instances.remove("_Total");
        // At this point we have a list of strings that PDH understands. Fetch
        // the counters.
        // Although the field names say "PerSec" this is the Raw Data/counters
        // from which the associated fields are populated in the Formatted Data
        for (String i : instances) {
            String name = ParseUtil.whitespaces.split(i)[0];
            String readString = String.format(PDH_DISK_READS_FORMAT, i);
            if (!PerfDataUtil.isCounter(readString)) {
                PerfDataUtil.addCounter(readString);
            }
            String readBytesString = String.format(PDH_DISK_READ_BYTES_FORMAT, i);
            if (!PerfDataUtil.isCounter(readBytesString)) {
                PerfDataUtil.addCounter(readBytesString);
            }
            String writeString = String.format(PDH_DISK_WRITES_FORMAT, i);
            if (!PerfDataUtil.isCounter(writeString)) {
                PerfDataUtil.addCounter(writeString);
            }
            String writeBytesString = String.format(PDH_DISK_WRITE_BYTES_FORMAT, i);
            if (!PerfDataUtil.isCounter(writeBytesString)) {
                PerfDataUtil.addCounter(writeBytesString);
            }
            String xferTimeString = String.format(PDH_DISK_TIME_FORMAT, i);
            if (!PerfDataUtil.isCounter(xferTimeString)) {
                PerfDataUtil.addCounter(xferTimeString);
            }
            readMap.put(name, PerfDataUtil.queryCounter(readString));
            readByteMap.put(name, PerfDataUtil.queryCounter(readBytesString));
            writeMap.put(name, PerfDataUtil.queryCounter(writeString));
            writeByteMap.put(name, PerfDataUtil.queryCounter(writeBytesString));
            xferTimeMap.put(name, PerfDataUtil.queryCounter(xferTimeString) / 10000L);
            timeStampMap.put(name, PerfDataUtil.queryCounterTimestamp(xferTimeString));
        }
    }

    private void populatePartitionMaps() {
        driveToPartitionMap.clear();
        partitionToLogicalDriveMap.clear();
        partitionMap.clear();
        // For Regexp matching DeviceIDs
        Matcher mAnt;
        Matcher mDep;

        // Map drives to partitions
        WmiResult<DriveToPartitionProperty> drivePartitionMap = WmiUtil.queryWMI(DRIVE_TO_PARTITION_QUERY);
        for (int i = 0; i < drivePartitionMap.getResultCount(); i++) {
            mAnt = DEVICE_ID.matcher(drivePartitionMap.getString(DriveToPartitionProperty.ANTECEDENT, i));
            mDep = DEVICE_ID.matcher(drivePartitionMap.getString(DriveToPartitionProperty.DEPENDENT, i));
            if (mAnt.matches() && mDep.matches()) {
                MapUtil.createNewListIfAbsent(driveToPartitionMap, mAnt.group(1).replaceAll("\\\\\\\\", "\\\\"))
                        .add(mDep.group(1));
            }
        }

        // Map partitions to logical disks
        WmiResult<DriveToPartitionProperty> diskPartitionMap = WmiUtil.queryWMI(DISK_TO_PARTITION_QUERY);
        for (int i = 0; i < diskPartitionMap.getResultCount(); i++) {
            mAnt = DEVICE_ID.matcher(diskPartitionMap.getString(DriveToPartitionProperty.ANTECEDENT, i));
            mDep = DEVICE_ID.matcher(diskPartitionMap.getString(DriveToPartitionProperty.DEPENDENT, i));
            if (mAnt.matches() && mDep.matches()) {
                partitionToLogicalDriveMap.put(mAnt.group(1), mDep.group(1) + "\\");
            }
        }

        // Next, get all partitions and create objects
        WmiResult<DiskPartitionProperty> hwPartitionQueryMap = WmiUtil.queryWMI(PARTITION_QUERY);
        for (int i = 0; i < hwPartitionQueryMap.getResultCount(); i++) {
            String deviceID = hwPartitionQueryMap.getString(DiskPartitionProperty.DEVICEID, i);
            String logicalDrive = MapUtil.getOrDefault(partitionToLogicalDriveMap, deviceID, "");
            String uuid = "";
            if (!logicalDrive.isEmpty()) {
                // Get matching volume for UUID
                char[] volumeChr = new char[BUFSIZE];
                Kernel32.INSTANCE.GetVolumeNameForVolumeMountPoint(logicalDrive, volumeChr, BUFSIZE);
                uuid = ParseUtil.parseUuidOrDefault(new String(volumeChr).trim(), "");
            }
            partitionMap.put(deviceID,
                    new HWPartition(hwPartitionQueryMap.getString(DiskPartitionProperty.NAME, i),
                            hwPartitionQueryMap.getString(DiskPartitionProperty.TYPE, i),
                            hwPartitionQueryMap.getString(DiskPartitionProperty.DESCRIPTION, i), uuid,
                            ParseUtil.parseLongOrDefault(hwPartitionQueryMap.getString(DiskPartitionProperty.SIZE, i),
                                    0L),
                            hwPartitionQueryMap.getInteger(DiskPartitionProperty.DISKINDEX, i),
                            hwPartitionQueryMap.getInteger(DiskPartitionProperty.INDEX, i), logicalDrive));
        }
    }
}
