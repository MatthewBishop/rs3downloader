package com.method.rscd;

import com.method.rscd.cache.FileStore;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.method.rscd.CacheDownloader.MUSIC_INDEX;

public class CachePacker {

    public static void main(String[] args) {
        initializeNewCache();
    }


    public static void initializeNewCache() {

        //the total number of cache indexes
        int count = 62;

        //the index to pack to
        //int target_idx = 7;

        int[] to_pack = new int[] { 2 };

        try {

            RandomAccessFile dataFile = new RandomAccessFile("./data/main_file_cache.dat2", "rw");
            RandomAccessFile musicDataFile = new RandomAccessFile("./data/main_file_cache.dat2m", "rw");
            RandomAccessFile referenceFile = new RandomAccessFile("./data/main_file_cache.idx255", "rw");
            FileStore reference = new FileStore(255, dataFile.getChannel(), referenceFile.getChannel(), 2000000);

            FileStore[] stores = new FileStore[count];
            for (int i = 0; i < count; i++) {
                RandomAccessFile indexFile = new RandomAccessFile("./data/main_file_cache.idx" + i, "rw");
                stores[i] = new FileStore(i, i == MUSIC_INDEX ? musicDataFile.getChannel() : dataFile.getChannel(), indexFile.getChannel(), 10000000);
            }

            for(int target_idx : to_pack) {
                {
                    byte[] data = Files.readAllBytes(Path.of("C:\\Users\\Seb\\Desktop\\cache\\255\\" + target_idx + ".dat"));
                    reference.put(target_idx, ByteBuffer.wrap(data), data.length);
                }
                String base = "C:\\Users\\Seb\\Desktop\\cache\\"+target_idx+"\\";
                File dir = new File(base);
                for (File child :  dir.listFiles()) {
                    // Do something with child
                    byte[] data = Files.readAllBytes(child.toPath());
                    //        System.out.println(child.getName());
                    int file = Integer.valueOf(child.getName().split("\\.")[0]);
                    stores[target_idx].put(file, ByteBuffer.wrap(data), data.length);
                }
            }
        } catch (IOException ioex) {
            ioex.printStackTrace();
        }
    }

}
