package com.meinos.meditationanalyzer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Compact CosmOS Audio Storage (.cfs / .tba / .wav) - bit-for-bit compatible
 * with desktop cosmos_audio_storage.py:
 * - 4-bit IMA-ADPCM (high-nibble first, 4:1 over 16-bit PCM)
 * - TBA4 container (TBAHeader + TBAFileEntry + 64KB LZ77 blocks)
 * - CFS1 Crystal File System image (CFS1 superblock + ZLB1 zlib stream)
 */
public class CosmosAudioStorage {

    private static final int[] IMA_INDEX_TABLE = {
            -1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8
    };

    private static final int[] IMA_STEP_TABLE = {
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31,
            34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143,
            157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658,
            724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024,
            3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
            15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    };

    private static final int ADPC_HDR_SIZE = 24;
    private static final int TBA_HEADER_SIZE = 16;
    private static final int TBA_ENTRY_SIZE = 544;
    private static final int TBA_BLOCK_SIZE = 65536;

    private static final int CFS_MAGIC = 0x43465331;
    private static final int CFS_SECTOR_SIZE = 512;
    private static final int CFS_SECTORS_PER_CLUSTER = 8;
    private static final int CFS_DIR_ENTRY_SIZE = 64;

    public static class SaveResult {
        public String path;
        public String format;
        public int sampleRate;
        public int samples;
        public long rawBytes;
        public long storedBytes;
        public double ratio;
    }

    public static class LoadedAudio {
        public short[] pcm;
        public int sampleRate;
        public String format;
        public long storedBytes;
        public double ratio;
    }

    public static byte[] encodeImaAdpcm(short[] pcm, int length) {
        if (pcm == null || length <= 0) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream((length + 1) / 2);
        int pred = 0;
        int stepIdx = 0;
        boolean haveHigh = false;
        int curByte = 0;

        for (int i = 0; i < length; i++) {
            int s = pcm[i];
            int step = IMA_STEP_TABLE[stepIdx];
            int diff = s - pred;
            int nibble = 0;
            if (diff < 0) {
                nibble = 8;
                diff = -diff;
            }
            int vpdiff = step >> 3;
            int t = step;
            if (diff >= t) { nibble |= 4; diff -= t; vpdiff += t; }
            t = step >> 1;
            if (diff >= t) { nibble |= 2; diff -= t; vpdiff += t; }
            t = step >> 2;
            if (diff >= t) { nibble |= 1; vpdiff += t; }

            if ((nibble & 8) != 0) pred -= vpdiff;
            else pred += vpdiff;
            if (pred > 32767) pred = 32767;
            else if (pred < -32768) pred = -32768;

            stepIdx = Math.max(0, Math.min(88, stepIdx + IMA_INDEX_TABLE[nibble]));
            if (!haveHigh) {
                curByte = (nibble & 0x0F) << 4;
                haveHigh = true;
            } else {
                out.write(curByte | (nibble & 0x0F));
                haveHigh = false;
            }
        }
        if (haveHigh) out.write(curByte);
        return out.toByteArray();
    }

    public static short[] decodeImaAdpcm(byte[] adpcm, int numSamples) {
        if (adpcm == null || adpcm.length == 0 || numSamples <= 0) return new short[0];
        short[] out = new short[numSamples];
        int outPos = 0;
        int pred = 0;
        int stepIdx = 0;

        for (int i = 0; i < adpcm.length && outPos < numSamples; i++) {
            int b = adpcm[i] & 0xFF;
            for (int shift = 4; shift >= 0 && outPos < numSamples; shift -= 4) {
                int nibble = (b >> shift) & 0x0F;
                int step = IMA_STEP_TABLE[stepIdx];
                int diff = step >> 3;
                if ((nibble & 4) != 0) diff += step;
                if ((nibble & 2) != 0) diff += step >> 1;
                if ((nibble & 1) != 0) diff += step >> 2;
                if ((nibble & 8) != 0) pred -= diff;
                else pred += diff;
                if (pred > 32767) pred = 32767;
                else if (pred < -32768) pred = -32768;
                stepIdx = Math.max(0, Math.min(88, stepIdx + IMA_INDEX_TABLE[nibble]));
                out[outPos++] = (short) pred;
            }
        }
        return out;
    }

    public static byte[] packAudioPayload(short[] pcm, int length, int sampleRate) {
        byte[] adpcm = encodeImaAdpcm(pcm, length);
        ByteBuffer bb = ByteBuffer.allocate(ADPC_HDR_SIZE + adpcm.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(new byte[]{'A', 'D', 'P', 'C'});
        bb.putInt(sampleRate);
        bb.putShort((short) 1);
        bb.putShort((short) 4);
        bb.putInt(length);
        bb.putInt(length * 2);
        bb.putInt(adpcm.length);
        bb.put(adpcm);
        return bb.array();
    }

    public static LoadedAudio unpackAudioPayload(byte[] payload) throws IOException {
        if (payload == null || payload.length < ADPC_HDR_SIZE) {
            throw new IOException("Truncated ADPC audio payload");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        bb.get(magic);
        if (magic[0] != 'A' || magic[1] != 'D' || magic[2] != 'P' || magic[3] != 'C') {
            throw new IOException("Unexpected ADPC payload magic");
        }
        int sr = bb.getInt();
        bb.getShort(); // channels
        bb.getShort(); // bps
        int numSamples = bb.getInt();
        bb.getInt(); // orig bytes
        int adpcmLen = bb.getInt();
        int avail = Math.min(adpcmLen, payload.length - ADPC_HDR_SIZE);
        byte[] body = new byte[Math.max(0, avail)];
        bb.get(body);
        LoadedAudio res = new LoadedAudio();
        res.pcm = decodeImaAdpcm(body, numSamples);
        res.sampleRate = sr;
        return res;
    }

    private static class BitWriter {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int acc = 0;
        int nBits = 0;

        void write(int val, int count) {
            acc |= (val & ((1 << count) - 1)) << nBits;
            nBits += count;
            while (nBits >= 8) {
                out.write(acc & 0xFF);
                acc >>= 8;
                nBits -= 8;
            }
        }

        byte[] finish() {
            if (nBits > 0) out.write(acc & 0xFF);
            return out.toByteArray();
        }
    }

    private static byte[] compressTbaBlock(byte[] block, int off, int n) {
        BitWriter bw = new BitWriter();
        int[] head = new int[65536];
        Arrays.fill(head, -1);
        int i = 0;
        while (i < n) {
            int bestLen = 0;
            int bestDist = 0;
            if (i + 3 <= n) {
                int p = off + i;
                int h = (((block[p] & 0xFF) * 73856093) ^ ((block[p + 1] & 0xFF) * 19349663) ^ ((block[p + 2] & 0xFF) * 83492791)) & 0xFFFF;
                int prev = head[h];
                head[h] = i;
                if (prev >= 0) {
                    int dist = i - prev;
                    if (dist >= 1 && dist <= 32767
                            && block[off + prev] == block[p]
                            && block[off + prev + 1] == block[p + 1]
                            && block[off + prev + 2] == block[p + 2]) {
                        int maxM = Math.min(255, n - i);
                        int m = 3;
                        while (m < maxM && block[off + prev + m] == block[p + m]) m++;
                        bestLen = m;
                        bestDist = dist;
                    }
                }
            }
            if (bestLen >= 3) {
                bw.write(1, 1);
                bw.write(bestLen, 8);
                bw.write(bestDist, 15);
                int limit = Math.min(i + bestLen, Math.min(n - 2, i + 8));
                for (int k = i + 1; k < limit; k++) {
                    int pk = off + k;
                    int hk = (((block[pk] & 0xFF) * 73856093) ^ ((block[pk + 1] & 0xFF) * 19349663) ^ ((block[pk + 2] & 0xFF) * 83492791)) & 0xFFFF;
                    head[hk] = k;
                }
                i += bestLen;
            } else {
                bw.write(0, 1);
                bw.write(block[off + i] & 0xFF, 8);
                i++;
            }
        }
        return bw.finish();
    }

    private static byte[] decompressTbaBlock(byte[] src, int off, int len, int origLen) {
        byte[] dst = new byte[origLen];
        int dstPos = 0;
        int acc = 0;
        int nBits = 0;
        int idx = 0;

        while (dstPos < origLen && idx <= len) {
            while (nBits < 1 && idx < len) {
                acc |= (src[off + idx++] & 0xFF) << nBits;
                nBits += 8;
            }
            int flag = acc & 1;
            acc >>= 1;
            nBits -= 1;

            if (flag == 1) {
                while (nBits < 8 && idx < len) {
                    acc |= (src[off + idx++] & 0xFF) << nBits;
                    nBits += 8;
                }
                int matchLen = acc & 0xFF;
                acc >>= 8;
                nBits -= 8;

                while (nBits < 15 && idx < len) {
                    acc |= (src[off + idx++] & 0xFF) << nBits;
                    nBits += 8;
                }
                int dist = acc & 0x7FFF;
                acc >>= 15;
                nBits -= 15;

                if (dstPos >= dist && dist > 0) {
                    int start = dstPos - dist;
                    for (int k = 0; k < matchLen && dstPos < origLen; k++) {
                        dst[dstPos++] = dst[start + k];
                    }
                } else {
                    for (int k = 0; k < matchLen && dstPos < origLen; k++) {
                        dst[dstPos++] = 0;
                    }
                }
            } else {
                while (nBits < 8 && idx < len) {
                    acc |= (src[off + idx++] & 0xFF) << nBits;
                    nBits += 8;
                }
                if (dstPos < origLen) {
                    dst[dstPos++] = (byte) (acc & 0xFF);
                }
                acc >>= 8;
                nBits -= 8;
            }
        }
        return dst;
    }

    public static byte[] encodeTbaContainer(String entryName, byte[] payload) {
        int origSize = payload.length;
        int bCnt = origSize == 0 ? 0 : (origSize + TBA_BLOCK_SIZE - 1) / TBA_BLOCK_SIZE;
        byte[] bitmap = new byte[(bCnt + 7) / 8];
        ByteArrayOutputStream packedBlocks = new ByteArrayOutputStream();

        for (int bIdx = 0; bIdx < bCnt; bIdx++) {
            int off = bIdx * TBA_BLOCK_SIZE;
            int blkLen = Math.min(TBA_BLOCK_SIZE, origSize - off);
            byte[] comp = compressTbaBlock(payload, off, blkLen);
            if (comp.length + 4 < blkLen) {
                bitmap[bIdx / 8] |= (1 << (bIdx % 8));
                ByteBuffer lb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(comp.length);
                packedBlocks.write(lb.array(), 0, 4);
                packedBlocks.write(comp, 0, comp.length);
            } else {
                packedBlocks.write(payload, off, blkLen);
            }
        }

        byte[] pb = packedBlocks.toByteArray();
        int compStreamLen = 4 + bitmap.length + pb.length;
        int dataOffset = TBA_HEADER_SIZE + TBA_ENTRY_SIZE;

        ByteBuffer out = ByteBuffer.allocate(dataOffset + compStreamLen).order(ByteOrder.LITTLE_ENDIAN);
        // Header (16 bytes)
        out.put(new byte[]{'T', 'B', 'A', '4'});
        out.putInt(1);
        out.putInt(TBA_HEADER_SIZE);
        out.putShort((short) 0xA101);
        out.putShort((short) 100);

        // Entry (544 bytes)
        byte[] nameBytes = new byte[512];
        byte[] nb = entryName.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nb, 0, nameBytes, 0, Math.min(511, nb.length));
        out.put(nameBytes);
        out.putLong(dataOffset);
        out.putLong(origSize);
        out.putLong(compStreamLen);
        out.put((byte) 1);
        out.put((byte) 0);
        out.put(new byte[6]);

        // Stream
        out.putInt(bCnt);
        out.put(bitmap);
        out.put(pb);
        return out.array();
    }

    public static byte[] decodeTbaContainer(byte[] raw) throws IOException {
        if (raw == null || raw.length < TBA_HEADER_SIZE + TBA_ENTRY_SIZE) {
            throw new IOException("Invalid .tba file (too small)");
        }
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        bb.get(magic);
        int fileCount = bb.getInt();
        if (magic[0] != 'T' || magic[1] != 'B' || magic[2] != 'A' || magic[3] != '4' || fileCount < 1) {
            throw new IOException("Unsupported .tba header");
        }
        bb.position(TBA_HEADER_SIZE + 512);
        long offsetLong = bb.getLong();
        long origSzLong = bb.getLong();
        bb.getLong(); // comp_sz
        int isComp = bb.get() & 0xFF;
        int isPure = bb.get() & 0xFF;
        int offset = (int) offsetLong;
        int origSz = (int) origSzLong;

        if (isPure == 1 || isComp == 0) {
            return Arrays.copyOfRange(raw, offset, Math.min(raw.length, offset + origSz));
        }

        bb.position(offset);
        int bCnt = bb.getInt();
        int mapBytes = (bCnt + 7) / 8;
        byte[] bitmap = new byte[mapBytes];
        bb.get(bitmap);
        int pos = bb.position();

        ByteArrayOutputStream out = new ByteArrayOutputStream(origSz);
        for (int bIdx = 0; bIdx < bCnt; bIdx++) {
            int blkLen = TBA_BLOCK_SIZE;
            if (bIdx == bCnt - 1 && (origSz % TBA_BLOCK_SIZE) != 0) {
                blkLen = origSz % TBA_BLOCK_SIZE;
            }
            boolean isBlkComp = (bitmap[bIdx / 8] & (1 << (bIdx % 8))) != 0;
            if (isBlkComp) {
                int cSize = ByteBuffer.wrap(raw, pos, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                pos += 4;
                byte[] dec = decompressTbaBlock(raw, pos, cSize, blkLen);
                out.write(dec, 0, dec.length);
                pos += cSize;
            } else {
                out.write(raw, pos, blkLen);
                pos += blkLen;
            }
        }
        byte[] all = out.toByteArray();
        return all.length == origSz ? all : Arrays.copyOf(all, origSz);
    }

    private static byte[] zlibCompress(byte[] input) {
        Deflater deflater = new Deflater(6);
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length / 2);
        byte[] buf = new byte[4096];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            out.write(buf, 0, n);
        }
        deflater.end();
        return out.toByteArray();
    }

    private static byte[] zlibDecompress(byte[] input, int off, int len) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(input, off, len);
        ByteArrayOutputStream out = new ByteArrayOutputStream(len * 2);
        byte[] buf = new byte[4096];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0) break;
                out.write(buf, 0, n);
            }
        } catch (DataFormatException e) {
            throw new IOException("Corrupt zlib stream in .cfs", e);
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    public static byte[] encodeCfsContainer(String filename, byte[] payload) {
        byte[] compInner = zlibCompress(payload);
        byte[] stored;
        if (compInner.length + 8 < payload.length) {
            ByteBuffer zb = ByteBuffer.allocate(8 + compInner.length).order(ByteOrder.LITTLE_ENDIAN);
            zb.put(new byte[]{'Z', 'L', 'B', '1'});
            zb.putInt(payload.length);
            zb.put(compInner);
            stored = zb.array();
        } else {
            stored = payload;
        }

        int bitmapLba = 2;
        int bitmapSectors = 4;
        int rootDirLba = bitmapLba + bitmapSectors;
        int rootDirSectors = 4;
        int dataStartLba = rootDirLba + rootDirSectors;

        int neededDataSectors = Math.max(8, (stored.length + CFS_SECTOR_SIZE - 1) / CFS_SECTOR_SIZE);
        int clusterAlign = ((neededDataSectors + CFS_SECTORS_PER_CLUSTER - 1) / CFS_SECTORS_PER_CLUSTER) * CFS_SECTORS_PER_CLUSTER;
        int totalSectors = dataStartLba + clusterAlign;

        byte[] img = new byte[totalSectors * CFS_SECTOR_SIZE];
        ByteBuffer bb = ByteBuffer.wrap(img).order(ByteOrder.LITTLE_ENDIAN);

        // Superblock at LBA 1 (offset 512)
        bb.position(CFS_SECTOR_SIZE);
        bb.putInt(CFS_MAGIC);
        bb.putInt(1);
        bb.putInt(CFS_SECTOR_SIZE);
        bb.putInt(CFS_SECTORS_PER_CLUSTER);
        bb.putLong(totalSectors);
        bb.putLong(bitmapLba);
        bb.putLong(rootDirLba);
        bb.putLong(dataStartLba);
        byte[] vol = "COSMOS_AUDIO_CFS".getBytes(StandardCharsets.UTF_8);
        bb.put(vol, 0, Math.min(32, vol.length));

        // Bitmap at LBA 2
        int numClusters = clusterAlign / CFS_SECTORS_PER_CLUSTER;
        int bmpOff = bitmapLba * CFS_SECTOR_SIZE;
        for (int c = 0; c < numClusters; c++) {
            img[bmpOff + (c / 8)] |= (1 << (c % 8));
        }

        // Directory entry at rootDirLba
        bb.position(rootDirLba * CFS_SECTOR_SIZE);
        bb.put((byte) 1);
        bb.put((byte) 0);
        byte[] fname = new byte[46];
        byte[] fn = filename.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(fn, 0, fname, 0, Math.min(45, fn.length));
        bb.put(fname);
        bb.putLong(dataStartLba);
        bb.putLong(stored.length);

        // Data payload
        System.arraycopy(stored, 0, img, dataStartLba * CFS_SECTOR_SIZE, stored.length);
        return img;
    }

    public static byte[] decodeCfsContainer(byte[] raw) throws IOException {
        if (raw == null || raw.length < CFS_SECTOR_SIZE * 4) {
            throw new IOException("Invalid .cfs image");
        }
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        bb.position(CFS_SECTOR_SIZE);
        int magic = bb.getInt();
        bb.getInt(); // version
        int secSz = bb.getInt();
        bb.getInt(); // spc
        bb.getLong(); // total
        bb.getLong(); // bitmap lba
        long rootLba = bb.getLong();
        if (magic != CFS_MAGIC || secSz != CFS_SECTOR_SIZE) {
            throw new IOException("Unsupported CFS magic");
        }

        bb.position((int) rootLba * CFS_SECTOR_SIZE);
        int etype = bb.get() & 0xFF;
        bb.get(); // flags
        bb.position(bb.position() + 46);
        long startLba = bb.getLong();
        long fileSzLong = bb.getLong();
        if (etype == 0 || fileSzLong <= 0) {
            throw new IOException("No audio entry inside .cfs image");
        }
        int dataOff = (int) startLba * CFS_SECTOR_SIZE;
        int fileSz = (int) fileSzLong;
        if (fileSz >= 8
                && raw[dataOff] == 'Z' && raw[dataOff + 1] == 'L'
                && raw[dataOff + 2] == 'B' && raw[dataOff + 3] == '1') {
            return zlibDecompress(raw, dataOff + 8, fileSz - 8);
        }
        return Arrays.copyOfRange(raw, dataOff, dataOff + fileSz);
    }

    public static SaveResult saveRecording(File file, short[] pcm, int length, int sampleRate) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        String name = file.getName().toLowerCase();
        String base = file.getName();
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;

        byte[] blob;
        String fmtName;
        if (name.endsWith(".wav")) {
            ByteBuffer wb = ByteBuffer.allocate(44 + length * 2).order(ByteOrder.LITTLE_ENDIAN);
            int pcmBytes = length * 2;
            wb.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(pcmBytes + 36).put(new byte[]{'W', 'A', 'V', 'E'});
            wb.put(new byte[]{'f', 'm', 't', ' '}).putInt(16).putShort((short) 1).putShort((short) 1)
                    .putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
            wb.put(new byte[]{'d', 'a', 't', 'a'}).putInt(pcmBytes);
            for (int i = 0; i < length; i++) wb.putShort(pcm[i]);
            blob = wb.array();
            fmtName = "WAV";
        } else if (name.endsWith(".tba")) {
            byte[] payload = packAudioPayload(pcm, length, sampleRate);
            blob = encodeTbaContainer(stem + ".ADPC", payload);
            fmtName = "TBA4+ADPCM";
        } else {
            byte[] payload = packAudioPayload(pcm, length, sampleRate);
            blob = encodeCfsContainer(stem + ".ADPC", payload);
            fmtName = "CFS1+ADPCM";
        }

        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(blob);
        }

        SaveResult res = new SaveResult();
        res.path = file.getAbsolutePath();
        res.format = fmtName;
        res.sampleRate = sampleRate;
        res.samples = length;
        res.rawBytes = (long) length * 2L;
        res.storedBytes = blob.length;
        res.ratio = res.rawBytes / (double) Math.max(1L, res.storedBytes);
        return res;
    }

    public static LoadedAudio loadRecording(File file) throws IOException {
        byte[] raw = readAllBytes(file);
        return loadRecordingBytes(raw, file.getName());
    }

    public static LoadedAudio loadRecordingBytes(byte[] raw, String filename) throws IOException {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".wav") || (raw.length >= 12 && raw[0] == 'R' && raw[1] == 'I' && raw[2] == 'F' && raw[3] == 'F')) {
            return parseWav(raw);
        }
        byte[] payload;
        String fmt;
        if (lower.endsWith(".tba") || (raw.length >= 4 && raw[0] == 'T' && raw[1] == 'B' && raw[2] == 'A' && raw[3] == '4')) {
            payload = decodeTbaContainer(raw);
            fmt = "TBA4";
        } else {
            payload = decodeCfsContainer(raw);
            if (payload.length >= 4 && payload[0] == 'T' && payload[1] == 'B' && payload[2] == 'A' && payload[3] == '4') {
                payload = decodeTbaContainer(payload);
            }
            fmt = "CFS1";
        }
        LoadedAudio audio = unpackAudioPayload(payload);
        audio.format = fmt;
        audio.storedBytes = raw.length;
        long rawBytes = (long) audio.pcm.length * 2L;
        audio.ratio = rawBytes / (double) Math.max(1L, audio.storedBytes);
        return audio;
    }

    private static LoadedAudio parseWav(byte[] raw) throws IOException {
        if (raw.length < 44) throw new IOException("Invalid WAV");
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int sr = bb.getInt(24);
        int ch = bb.getShort(22) & 0xFFFF;
        int pos = 12;
        int dataOff = -1;
        int dataLen = 0;
        while (pos + 8 <= raw.length) {
            byte c0 = raw[pos], c1 = raw[pos + 1], c2 = raw[pos + 2], c3 = raw[pos + 3];
            int chunkSz = bb.getInt(pos + 4);
            if (c0 == 'd' && c1 == 'a' && c2 == 't' && c3 == 'a') {
                dataOff = pos + 8;
                dataLen = Math.min(chunkSz, raw.length - dataOff);
                break;
            }
            pos += 8 + chunkSz + (chunkSz & 1);
        }
        if (dataOff < 0) throw new IOException("WAV missing data chunk");
        int frames = dataLen / (2 * Math.max(1, ch));
        short[] pcm = new short[frames];
        bb.position(dataOff);
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < ch; c++) sum += bb.getShort();
            pcm[i] = (short) (sum / Math.max(1, ch));
        }
        LoadedAudio res = new LoadedAudio();
        res.pcm = pcm;
        res.sampleRate = sr > 0 ? sr : 44100;
        res.format = "WAV";
        res.storedBytes = raw.length;
        res.ratio = 1.0;
        return res;
    }

    public static byte[] readAllBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream((int) file.length())) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = fis.read(buf)) != -1) bos.write(buf, 0, r);
            return bos.toByteArray();
        }
    }
}
