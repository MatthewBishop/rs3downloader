package com.method.rscd.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HttpWorker {

	private String host;
	private ExecutorService executor;

	public HttpWorker(String host) {
		this.host = host;
		this.executor = Executors.newFixedThreadPool(2);
	}

	public FileRequest request(int index, int file, int crc, int version) {
		FileRequest request = new FileRequest(index, file);
		executor.submit(new HttpTask(request, crc, version));
		return request;
	}

	public void shutdown() {
		executor.shutdown();
	}

	private class HttpTask implements Runnable {

		FileRequest request;
		int crc;
		int version;

		HttpTask(FileRequest request, int crc, int version) {
			this.request = request;
			this.crc = crc;
			this.version = version;
		}

		public void run() {
			URL url;
			try {
				url = new URL("http", host, 80, "/ms?m=0&a=" + request.getIndex() + "&g=" + request.getFile() + "&c=" + crc + "&v=" + version);
			} catch (MalformedURLException ex) {
				return;
			}

			InputStream is = null;
			try {
				URLConnection connection = url.openConnection();
				connection.setConnectTimeout(10000);
				connection.setReadTimeout(10000);
				is = connection.getInputStream();

				byte[] data = new byte[1024];
				byte[] buf = new byte[1024];
				int size = 0;
				int read = 0;

				while (read >= 0) {
					read = is.read(buf);
					if (read > 0) {
						if (read + size > data.length) {
							byte[] copy = data;
							data = new byte[size * 2];
							System.arraycopy(copy, 0, data, 0, size);
						}

						System.arraycopy(buf, 0, data, size, read);
						size += read;
					}
				}

				request.setSize(size + 2);
				request.getBuffer().put(data, 0, size);
				request.getBuffer().flip();
				request.setComplete(true);
			} catch (IOException ex) {
				System.out.println("\nError downloading " + request.getIndex() + "," + request.getFile() + ": " + ex.getMessage());
				return;
			} finally {
				if (is != null) {
					try {
						is.close();
					} catch (IOException ignored) {

					}
				}
			}
		}
	}

}
