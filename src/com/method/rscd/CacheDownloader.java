package com.method.rscd;

import com.method.rscd.cache.FileStore;
import com.method.rscd.cache.ReferenceTable;
import com.method.rscd.net.FileRequest;
import com.method.rscd.net.HttpWorker;
import com.method.rscd.net.Js5Worker;
import com.method.rscd.util.Crawler;
import com.method.rscd.util.Whirlpool;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.zip.CRC32;

/**
 * Coordinates downloading and saving of the cache from the server.
 */
public class CacheDownloader {

	private static final int MAJOR_VERSION = 925;
	private static final int MINOR_VERSION = 1;
	private static final String HOST = "content.runescape.com";
	private static final int WORLD = 2;
	private static final int LANGUAGE = 0; // English language by default

	private static final int MUSIC_INDEX = 40;
	private static final int PROGRESS_TICKS = 20;

	private Js5Worker js5Worker;
	private HttpWorker httpWorker;
	private ReferenceTable versionTable;
	private ReferenceTable[] tables;
	private ReferenceTable[] oldTables;
	private FileStore reference;
	private FileStore[] stores;

	/**
	 * Entry point for the application. This method creates a new CacheDownloader instance and runs it.
	 *
	 * @param args Arguments for the program
	 */
	public static void main(String[] args) {
		CacheDownloader downloader = new CacheDownloader();
		downloader.run();
	}

	/**
	 * Creates a new CacheDownloader object.
	 */
	public CacheDownloader() {
		js5Worker = new Js5Worker();
		httpWorker = new HttpWorker(HOST);
	}

	/**
	 * Initiates the connection to the server and downloads the cache.
	 */
	public void run() {
		connect();
		downloadVersionTable();
		initCacheIndices(versionTable.getEntryCount());
		initOldTables();
		downloadNewTables();
		update();

		httpWorker.shutdown();
	}

	/**
	 * Connects to the server, retrying as needed if the version is incorrect.
	 */
	private void connect() {
		String key = Crawler.getKey("http://world21.runescape.com/jav_config.ws?binaryType=2");
		if (key == null) {
			System.err.println("Couldn't find valid handshake key.");
			System.exit(1);
		}
		js5Worker.connect(HOST, MAJOR_VERSION, MINOR_VERSION, key, LANGUAGE);
		while (js5Worker.getState() != Js5Worker.State.CONNECTED) {
			js5Worker.process();
		}
		System.out.println("Successful connection");
	}

	/**
	 * Downloads the version table from the server.
	 */
	private void downloadVersionTable() {
		FileRequest mainRequest = js5Worker.request(255, 255);
		while (!mainRequest.isComplete()) {
			js5Worker.process();
		}
		versionTable = new ReferenceTable(mainRequest.getBuffer());
	}

	/**
	 * Reads the existing reference table data from the cache.
	 */
	private void initOldTables() {
		oldTables = new ReferenceTable[reference.getFileCount()];
		for (int i = 0; i < oldTables.length; i++) {
			ByteBuffer data = reference.get(i);
			if (data != null) {
				oldTables[i] = new ReferenceTable(i, data, null);
			}
		}
	}

	/**
	 * Finds reference tables that need updating.
	 *
	 * @return A list containing the reference tables that need updating.
	 */
	private List<Integer> findTableChanges() {
		List<Integer> changes = new ArrayList<Integer>();
		ReferenceTable.Entry[] entries = versionTable.getEntries();
		for (int i = 0; i < versionTable.getEntryCount(); i++) {
			ReferenceTable.Entry entry = entries[i];
			if (entry.getCRC() == 0 && entry.getVersion() == 0) {
				continue;
			}
			if (i >= oldTables.length) {
				changes.add(i);
			} else {
				ReferenceTable table = oldTables[i];
				if (table != null) {
					int crc = table.getCRC(), version = table.getVersion();
					if (crc != entry.getCRC() || version != entry.getVersion()) {
						changes.add(i);
					}
				} else {
					changes.add(i);
				}
			}
		}

		return changes;
	}

	/**
	 * Downloads the required reference tables.
	 */
	private void downloadNewTables() {
		List<Integer> changes = findTableChanges();
		Queue<FileRequest> requests = new LinkedList<FileRequest>();
		tables = new ReferenceTable[versionTable.getEntryCount()];
		for (int i = 0; i < changes.size(); i++) {
			requests.offer(js5Worker.request(255, changes.get(i)));
		}

		while (requests.size() > 0) {
			js5Worker.process();
			for (Iterator<FileRequest> iter = requests.iterator(); iter.hasNext(); ) {
				FileRequest request = iter.next();
				if (request.isComplete()) {
					int file = request.getFile();
					ByteBuffer data = request.getBuffer();
					tables[file] = new ReferenceTable(file, data, versionTable);

					data.position(0);
					reference.put(file, data, data.capacity());
					iter.remove();
				}
			}
		}
	}

	/**
	 * Finds the files that need updating within a particular cache index.
	 *
	 * @param index The index of the cache to look through
	 * @return A list containing the files that need to be updated.
	 */
	private List<Integer> findFileChanges(int index) {
		String message = "Checking index " + index;

		List<Integer> changes = new ArrayList<Integer>();
		ReferenceTable.Entry tableEntry = versionTable.getEntries()[index];
		if (tableEntry.getCRC() == 0 && tableEntry.getVersion() == 0) {
			return null;
		}
		if (index >= stores.length && tables[index] != null) {
			ReferenceTable.Entry[] entries = tables[index].getEntries();
			int count = entries.length;

			printProgress(0, count, message);

			for (int i = 0; i < count; i++) {
				changes.add(entries[i].getIndex());
			}

			printProgress(count, count, message);
		} else {
			CRC32 crc32 = new CRC32();
			ReferenceTable table = tables[index] != null || index >= oldTables.length ? tables[index] : oldTables[index];
			if (table == null) {
				return null;
			}

			ReferenceTable.Entry[] entries = table.getEntries();
			int entryCount = table.getEntryCount();
			int interval = Math.max(entryCount / 100, 1);
			for (int i = 0; i < entryCount; i++) {
				ReferenceTable.Entry entry = entries[i];
				int entryIndex = entry.getIndex();
				ByteBuffer buffer = stores[index].get(entryIndex);
				if (buffer == null) {
					if (index != 14 || entryIndex != 0) {
						changes.add(entryIndex);
					}
				} else {
					crc32.update(buffer.array(), 0, buffer.capacity() - 2);
					int crc = (int) crc32.getValue();
					crc32.reset();
					if (crc != entry.getCRC()) {
						changes.add(entryIndex);
						continue;
					}
					buffer.position(buffer.capacity() - 2);
					int version = buffer.getShort() & 0xffff;
					if (version != (entry.getVersion() & 0xffff)) {
						changes.add(entryIndex);
					}
				}

				if (i % interval == 0) {
					printProgress(i, entryCount, message);
				}
			}

			printProgress(entryCount, entryCount, message);
		}
		return changes;
	}

	/**
	 * Downloads all of the files from each index in the cache.
	 */
	private void update() {
		for (int i = 0; i < versionTable.getEntryCount(); i++) {
			String message = "Updating index " + i;

			List<Integer> changes = findFileChanges(i);
			if (changes == null || changes.size() == 0) {
				continue;
			}

			ReferenceTable table = tables[i] != null ? tables[i] : oldTables[i];
			CRC32 crc = new CRC32();

			Queue<FileRequest> requests = new LinkedList<FileRequest>();
			int total = changes.size();
			for (int j = 0; j < total; j++) {
				int file = changes.get(j);
				FileRequest request;

				if (i == MUSIC_INDEX) {
					request = httpWorker.request(i, file, table.getEntry(file).getCRC(), table.getEntry(file).getVersion());
				} else {
					request = js5Worker.request(i, file);
				}

				requests.offer(request);
			}

			printProgress(0, total, message);

			int count = 0;
			while (requests.size() > 0) {
				if (i != MUSIC_INDEX) {
					js5Worker.process();
				}

				for (Iterator<FileRequest> iter = requests.iterator(); iter.hasNext(); ) {
					FileRequest request = iter.next();
					if (request.isComplete()) {
						int file = request.getFile();
						ByteBuffer data = request.getBuffer();
						ReferenceTable.Entry entry = table.getEntry(file);

						crc.update(data.array(), 0, data.limit());
						if (entry.getCRC() != (int) crc.getValue()) {
							throw new RuntimeException("CRC mismatch " + i + "," + file + "," + entry.getCRC() + " - " + (int) crc.getValue());
						}
						crc.reset();

						byte[] entryDigest = entry.getDigest();
						if (entryDigest != null) {
							byte[] digest = Whirlpool.whirlpool(data.array(), 0, data.limit());
							for (int j = 0; j < 64; j++) {
								if (digest[j] != entryDigest[j]) {
									throw new RuntimeException("Digest mismatch " + i + "," + file);
								}
							}
						}

						int version = entry.getVersion();
						data.position(data.limit()).limit(data.capacity());
						data.put((byte) (version >>> 8));
						data.put((byte) version);
						data.flip();

						stores[i].put(file, data, data.capacity());
						iter.remove();

						count++;
						printProgress(count, total, message);
					}
				}
			}
		}
	}

	/**
	 * Initializes the cache indices.
	 *
	 * @param count The number of indices
	 */
	private void initCacheIndices(int count) {
		try {
			RandomAccessFile dataFile = new RandomAccessFile("./data/main_file_cache.dat2", "rw");
			RandomAccessFile musicDataFile = new RandomAccessFile("./data/main_file_cache.dat2m", "rw");
			RandomAccessFile referenceFile = new RandomAccessFile("./data/main_file_cache.idx255", "rw");
			reference = new FileStore(255, dataFile.getChannel(), referenceFile.getChannel(), 2000000);

			stores = new FileStore[count];
			for (int i = 0; i < count; i++) {
				RandomAccessFile indexFile = new RandomAccessFile("./data/main_file_cache.idx" + i, "rw");
				stores[i] = new FileStore(i, i == MUSIC_INDEX ? musicDataFile.getChannel() : dataFile.getChannel(), indexFile.getChannel(), 10000000);
			}

		} catch (IOException ioex) {
			ioex.printStackTrace();
		}
	}

	private static void printProgress(int current, int total, String message) {
		StringBuilder line = new StringBuilder();
		line.append('\r').append(message).append(" [");

		int ticks;
		int percent;
		if (current == total) {
			ticks = PROGRESS_TICKS;
			percent = 100;
		} else {
			ticks = current * PROGRESS_TICKS / total;
			percent = (int) (current * 100.0f / total);
		}

		for (int i = 0; i < ticks; i++) {
			line.append('=');
		}

		for (int i = 0; i < PROGRESS_TICKS - ticks; i++) {
			line.append(' ');
		}

		line.append("] ").append(percent).append("% ").append(current).append(" / ").append(total);
		if (current == total) {
			System.out.println(line.toString());
		} else {
			System.out.print(line.toString());
		}
	}

}
