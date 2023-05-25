package com.method.rscd.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;

public class Crawler {

	public static String getKey(String address) {
		URL url;
		try {
			url = new URL(address);
		} catch (MalformedURLException ex) {
			ex.printStackTrace();
			return null;
		}

		try {
			BufferedReader reader = new BufferedReader(new InputStreamReader(url.openConnection().getInputStream()));
			StringBuilder page = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				if(line.contains("param=29="))
					return line.substring(9);
				page.append(line);
			}
			return page.toString();
		} catch (IOException ex) {
			ex.printStackTrace();
			return null;
		}
	}

}
