package com.gdrivefs.test.util;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.gdrivefs.simplecache.Drive;
import com.gdrivefs.simplecache.File;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.ParentReference;

public class DriveBuilder
{
	public static final String TESTID = "0B9V4qybqtJE-djhyUDl5R1pKTHM";
	
	public static void main(String[] args) throws GeneralSecurityException, IOException
	{
		HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();

		FileDataStoreFactory dataStoreFactory = new FileDataStoreFactory(new java.io.File(new java.io.File(System.getProperty("user.home"), ".googlefs"), "auth"));
		JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
		GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(httpTransport, JSON_FACTORY, "930897891601-4mbqrmuu5osvk7j3vlkv8k59liot620f.apps.googleusercontent.com", "v18DcOoqIvmVgPVtisCijpTV", Collections.singleton(DriveScopes.DRIVE)).setDataStoreFactory(dataStoreFactory).build();
		Credential credential = new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user");

		com.google.api.services.drive.Drive remote = new com.google.api.services.drive.Drive.Builder(httpTransport, JSON_FACTORY, credential).setApplicationName("GDrive").build();
		
		boolean discovered = false;
		com.google.api.services.drive.Drive.Files.List lst = remote.files().list().setQ("'"+remote.about().get().execute().getRootFolderId()+"' in parents and trashed=false");
		do
		{
			FileList files = lst.execute();
			for(com.google.api.services.drive.model.File child : files.getItems())
				if(child.getTitle().equals("test"))
				{
					discovered = true;
					System.out.println("test id: "+child.getId());
				}
			lst.setPageToken(files.getNextPageToken());
		} while(lst.getPageToken() != null && lst.getPageToken().length() > 0);
		
		if(!discovered)
		{
			com.google.api.services.drive.model.File newRemoteDirectory = new com.google.api.services.drive.model.File();
			newRemoteDirectory.setTitle("test");
			newRemoteDirectory.setMimeType("application/vnd.google-apps.folder");
			newRemoteDirectory.setParents(Arrays.asList(new ParentReference().setId(remote.about().get().execute().getRootFolderId())));
			
			newRemoteDirectory = remote.files().insert(newRemoteDirectory).execute();
			System.out.println("new test id: "+newRemoteDirectory.getId());
		}
	}
	
	public static File cleanTestDir() throws IOException, GeneralSecurityException
	{
		HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();

		FileDataStoreFactory dataStoreFactory = new FileDataStoreFactory(new java.io.File(new java.io.File(System.getProperty("user.home"), ".googlefs"), "auth"));
		JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
		GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(httpTransport, JSON_FACTORY, "930897891601-4mbqrmuu5osvk7j3vlkv8k59liot620f.apps.googleusercontent.com", "v18DcOoqIvmVgPVtisCijpTV", Collections.singleton(DriveScopes.DRIVE)).setDataStoreFactory(dataStoreFactory).build();
		Credential credential = new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user");

		com.google.api.services.drive.Drive remote = new com.google.api.services.drive.Drive.Builder(httpTransport, JSON_FACTORY, credential).setApplicationName("GDrive").build();
		
		com.google.api.services.drive.Drive.Files.List lst = remote.files().list().setQ("'"+TESTID+"' in parents and trashed=false");
		final List<com.google.api.services.drive.model.File> googleChildren = new ArrayList<com.google.api.services.drive.model.File>();
		do
		{
			FileList files = lst.execute();
			for(com.google.api.services.drive.model.File child : files.getItems())
				googleChildren.add(child);
			lst.setPageToken(files.getNextPageToken());
		} while(lst.getPageToken() != null && lst.getPageToken().length() > 0);

		for(com.google.api.services.drive.model.File f : googleChildren)
		{
			remote.files().delete(f.getId()).execute();
		}
		
		com.gdrivefs.simplecache.Drive drive = new com.gdrivefs.simplecache.Drive(remote, httpTransport);

		
		return drive.getFile(TESTID);
	}

	public static File uncleanTestDir() throws IOException, GeneralSecurityException
	{
		HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();

		FileDataStoreFactory dataStoreFactory = new FileDataStoreFactory(new java.io.File(new java.io.File(System.getProperty("user.home"), ".googlefs"), "auth"));
		JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
		GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(httpTransport, JSON_FACTORY, "930897891601-4mbqrmuu5osvk7j3vlkv8k59liot620f.apps.googleusercontent.com", "v18DcOoqIvmVgPVtisCijpTV", Collections.singleton(DriveScopes.DRIVE)).setDataStoreFactory(dataStoreFactory).build();
		Credential credential = new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user");

		com.google.api.services.drive.Drive remote = new com.google.api.services.drive.Drive.Builder(httpTransport, JSON_FACTORY, credential).setApplicationName("GDrive").build();
		
		com.gdrivefs.simplecache.Drive drive = new com.gdrivefs.simplecache.Drive(remote, httpTransport);

		
		return drive.getFile(TESTID);
	}
	
}
