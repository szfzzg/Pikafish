package com.pikafish.xiangqi;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * 校验导入的外置引擎是否为 Android ARM64 可执行文件（ELF PIE）。
 */
public final class EngineFileChecker {

    private EngineFileChecker() {
    }

    public static void validate(File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("文件不存在");
        }
        byte[] header = new byte[64];
        try (FileInputStream in = new FileInputStream(file)) {
            if (in.read(header) < 52) {
                throw new IOException("不是有效的 ELF 文件");
            }
        }
        if ((header[0] & 0xFF) != 0x7F || header[1] != 'E'
                || header[2] != 'L' || header[3] != 'F') {
            throw new IOException("不是 ELF 文件");
        }
        if ((header[4] & 0xFF) != 2) {
            throw new IOException("不是 64 位 ELF 文件");
        }
        if ((header[5] & 0xFF) != 1) {
            throw new IOException("只支持小端 ELF 文件");
        }
        int type = u16(header, 16);
        int machine = u16(header, 18);
        if (machine != 0xB7) {
            throw new IOException("不是 ARM64 引擎（仅支持 arm64-v8a）");
        }
        if (type != 2 && type != 3) {
            throw new IOException("不是可执行文件");
        }
        if (type == 3) {
            long phoff = u64(header, 32);
            int phentsize = u16(header, 54);
            int phnum = u16(header, 56);
            if (phoff <= 0 || phentsize < 56 || phnum <= 0 || phnum > 512) {
                throw new IOException("ELF 程序头信息异常");
            }
            boolean hasInterp = false;
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(phoff);
                byte[] ph = new byte[phentsize];
                for (int i = 0; i < phnum; i++) {
                    raf.readFully(ph);
                    if (u32(ph, 0) == 3) {
                        hasInterp = true;
                        break;
                    }
                }
            }
            if (!hasInterp) {
                throw new IOException("这是动态库，不能直接运行；请选择 ARM64 可执行引擎文件");
            }
        }
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int u32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8)
                | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private static long u64(byte[] b, int o) {
        long value = 0;
        for (int i = 7; i >= 0; i--) {
            value = (value << 8) | (b[o + i] & 0xFFL);
        }
        return value;
    }
}
