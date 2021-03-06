package com.gdrivefs.test.cases;

import java.io.IOException;
import java.security.GeneralSecurityException;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.gdrivefs.simplecache.File;
import com.gdrivefs.test.util.DriveBuilder;
import com.gdrivefs.test.util.GoogleFilesystemRunner;

@RunWith(GoogleFilesystemRunner.class)
public class TestParents
{
	@Test
	public void testSimple(DriveBuilder builder) throws IOException, GeneralSecurityException, InterruptedException
	{
			File test = builder.cleanDriveDirectory();

			test.mkdir("foo");
			Assert.assertEquals(1, test.getParents().size());
			test.refresh();
			Assert.assertEquals(1, test.getParents().size());

			test = builder.uncleanDriveDirectory();
			Assert.assertEquals(1, test.getChildren("foo").get(0).getParents().size());
	}

	@Test
	public void testMultipleParents(DriveBuilder builder) throws IOException, GeneralSecurityException, InterruptedException
	{
			File test = builder.cleanDriveDirectory();

			File foo = test.mkdir("foo");
			File bar = test.mkdir("bar");
			File noise = test.mkdir("noise");

			foo.addChild(noise);
			bar.addChild(noise);
			test.removeChild(noise);
			Assert.assertEquals(2, noise.getParents().size());
	}

	@Test
	public void testDuplicates(DriveBuilder builder) throws IOException, GeneralSecurityException, InterruptedException
	{
			File test = builder.cleanDriveDirectory();

			Assert.assertEquals(0, test.getChildren().size());
			File foo = test.mkdir("foo");
			File noise1 = test.mkdir("noise");
			File noise2 = test.mkdir("noise");
			File noise3 = test.mkdir("noise");

			noise1.addChild(foo);
			noise2.addChild(foo);
			noise3.addChild(foo);
			test.removeChild(foo);

			noise1.addChild(foo);
			noise2.addChild(foo);
			noise3.addChild(foo);

			Assert.assertEquals(3, foo.getParents().size());
			test.refresh();
			Assert.assertEquals(3, foo.getParents().size());
			
			builder.flush();

			test = builder.uncleanDriveDirectory();
			Assert.assertEquals(3, test.getChildren("noise").get(2).getChildren("foo").get(0).getParents().size());
	}
}
