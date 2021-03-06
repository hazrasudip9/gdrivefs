package com.gdrivefs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;

import net.fusejna.DirectoryFiller;
import net.fusejna.ErrorCodes;
import net.fusejna.FuseJna;
import net.fusejna.StructFuseFileInfo.FileInfoWrapper;
import net.fusejna.StructFuseFileInfo.FileInfoWrapper.OpenMode;
import net.fusejna.StructStat.StatWrapper;
import net.fusejna.XattrFiller;
import net.fusejna.XattrListFiller;
import net.fusejna.types.TypeMode.ModeWrapper;
import net.fusejna.types.TypeMode.NodeType;
import net.fusejna.util.FuseFilesystemAdapterAssumeImplemented;

import com.gdrivefs.internal.FileWriteCollector;
import com.gdrivefs.simplecache.Drive;
import com.gdrivefs.simplecache.File;
import com.gdrivefs.util.Utils;
import com.google.api.client.http.HttpTransport;

public class GoogleDriveLinuxFs extends FuseFilesystemAdapterAssumeImplemented
{
	public static final String IDENTICAL_FORWARD_SLASH_CHARACTER = "∕";

	Drive drive;
	
	private Map<Long, File> fileHandles = new HashMap<Long, File>();  // TODO: Figure out when handles should be dropped
	private Map<File, FileWriteCollector> openFiles = new HashMap<File, FileWriteCollector>();
	private long nextFileHandleId = 1;
	
	public GoogleDriveLinuxFs(Drive drive, HttpTransport transport)
	{
		this.drive = drive;
	}

	public GoogleDriveLinuxFs setLoggingStatus(boolean isEnabled)
	{
		super.log(isEnabled);
		return this;
	}
	
	public void flush(boolean flushUploads) throws InterruptedException
	{
		drive.flush(flushUploads);
	}
	
	@Override
	public void beforeMount(java.io.File mountPoint)
	{
		try
		{
			FuseJna.unmount(mountPoint);
		}
		catch(IOException e)
		{
			e.printStackTrace();
		}
	}
	
	public synchronized File getRoot() throws IOException
	{
		return drive.getRoot();
	}

	@Override
	public int access(final String path, final int access)
	{
		return 0;
	}

	@Override
	public int create(String path, final ModeWrapper mode, final FileInfoWrapper info)
	{
		while(path.endsWith("/")) path = path.substring(0, path.length()-1);
		
		try
		{
			try { getPath(path); return -ErrorCodes.EEXIST(); }
			catch(NoSuchElementException e) { /* Do nothing, the directory doesn't yet exist */ }
			
			File parent;
			try { parent = getParentPath(path); }
			catch(NoSuchElementException e) { return -ErrorCodes.ENOENT(); }
			
			File newFile = parent.createFile(path.substring(path.lastIndexOf('/')+1));
			info.fh(nextFileHandleId++);
			fileHandles.put(info.fh(), newFile);
			return 0;
		}
		catch(IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	@Override
	public int getattr(final String path, final StatWrapper stat)
	{
		try
		{
			File f = getPath(path);
			
			if(f.isDirectory())
			{
				stat.setMode(NodeType.DIRECTORY, true, true, true, false, false, false, false, false, false);
				return 0;
			}
			else
			{
				long size = f.getSize();
				
				{ // There may be some unflushed writes that may have extended file length and need to be taken into account
					FileWriteCollector wc = openFiles.get(f);
					if(wc != null) size = Math.max(size, wc.getCurrentPosition());
				}
				
				stat.setMode(NodeType.FILE, true, true, false, false, false, false, false, false, false).size(size).mtime(f.getModified().getTime()/1000);
				return 0;
			}
		}
		catch(NoSuchElementException e)
		{
			return -ErrorCodes.ENOENT();			
		}
		catch(AmbiguousPathException e)
		{
			return -ErrorCodes.ENOTUNIQ();
		}
		catch(IOException e)
		{
			e.printStackTrace();
			throw new RuntimeException(e);
		}
		catch(Throwable e)
		{
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}
	

	private String getLastComponent(String path)
	{
		while (path.substring(path.length() - 1).equals("/")) {
			path = path.substring(0, path.length() - 1);
		}
		if (path.isEmpty()) {
			return "";
		}
		return path.substring(path.lastIndexOf("/") + 1);
	}

	private File getParentPath(String path) throws IOException
	{
		if("/".equals(path)) return drive.getRoot();
		while(path.endsWith("/")) path = path.substring(0, path.length()-1);
		path = path.substring(0, path.lastIndexOf("/"));
		if("".equals(path)) return getPath("/");
		else return getPath(path);
	}

	@Override
	public int mkdir(String path, final ModeWrapper mode)
	{
		System.out.println(path);
		while(path.endsWith("/")) path = path.substring(0, path.length()-1);
		
		try
		{
			try { getPath(path); return -ErrorCodes.EEXIST(); }
			catch(NoSuchElementException e) { /* Do nothing, the directory doesn't yet exist */ }
			
			File parent;
			try { parent = getParentPath(path); }
			catch(NoSuchElementException e) { return -ErrorCodes.ENOENT(); }
			
			parent.mkdir(path.substring(path.lastIndexOf('/')+1));
			return 0;
		}
		catch(IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	@Override
	public int open(final String path, final FileInfoWrapper info)
	{
		try
		{
			info.fh(nextFileHandleId++);
			fileHandles.put(info.fh(), getPath(path));
		}
		catch(NoSuchElementException e)
		{
			return -ErrorCodes.ENOENT();
		}
		catch(IOException e)
		{
			e.printStackTrace();
			return -ErrorCodes.EIO();
		}
		return 0;
	}

	@Override
	public int read(final String path, final ByteBuffer buffer, final long size, final long offset, final FileInfoWrapper info)
	{
		try
		{
			File f = info.fh() != 0 ? fileHandles.get(info.fh()) : getPath(path);

			if(f.isDirectory()) return -ErrorCodes.EISDIR();

			boolean isGoogleDoc = f.getMimeType().startsWith("application/vnd.google-apps.");
			if(isGoogleDoc) return -ErrorCodes.EMEDIUMTYPE();
			
			FileWriteCollector collector = openFiles.get(f);
			if (collector != null) {
				// need to flush to the simplecache, so that
				// the most up-to-date fragment is available for reads
				collector.flushCurrentFragmentToDb();
			}

			long chunkStart = offset;
			long end = offset + Math.min(size,  f.getSize()-offset);
			do {
				long chunkEnd = Math.min(Utils.roundUpToFragmentBoundary(chunkStart), end);
				int len = (int)(chunkEnd - chunkStart);
				byte[] chunk = f.read(len, chunkStart);
				buffer.put(chunk);
				chunkStart = chunkEnd;
			} while (chunkStart < end);
			return (int)(end-offset); // currently we always read exactly what is requested (up to file size)
		}
		catch(NoSuchElementException e)
		{
			return -ErrorCodes.ENOENT();
		}
		catch(IOException e)
		{
			e.printStackTrace();
			return -ErrorCodes.EIO();
		}
	}

	@Override
	public int readdir(final String path, final DirectoryFiller filler)
	{
		try
		{
			File directory = getPath(path);
			getParentPath(path).considerAsyncDirectoryRefresh(2, TimeUnit.MINUTES);
			if(!directory.isDirectory()) return -ErrorCodes.ENOTDIR();
			for(File child : directory.getChildren())
			{
				filler.add(forwardSlashHack(child.getTitle()));
			}
		}
		catch(NoSuchElementException e)
		{
			return -ErrorCodes.ENOENT();
		}
		catch(IOException e)
		{
			e.printStackTrace();
			return -ErrorCodes.EIO();
		}
        
		return 0;
	}
	
	private File getPath(String localPath) throws IOException
	{
		if(!localPath.startsWith("/")) throw new IllegalArgumentException("Expected local path to start with a slash ("+localPath+")");
		
		String[] pathElements = localPath.split("/");
		
		for(int i = 0; i < pathElements.length; i++) {
			pathElements[i] = forwardSlashHack(pathElements[i]);
		}
		
		File current = drive.getRoot();
		for(int i = 1; i < pathElements.length; i++)
		{
			if("".equals(pathElements[i])) continue;
			
			List<File> children = current.getChildren(pathElements[i]);
			if(children.isEmpty()) throw new NoSuchElementException(localPath);
			else if(children.size() == 1) current = children.get(0);
			else throw new AmbiguousPathException(localPath);
		}
		
		if(current.isDirectory()) current.considerAsyncDirectoryRefresh(1, TimeUnit.MINUTES);
		return current;
	}

	@Override
	public synchronized int rename(final String oldPath, final String newPath)
	{
		try
		{
			File file = getPath(oldPath);
			File oldParent = getParentPath(oldPath);
			File newParent = getParentPath(newPath);
			String oldName = getLastComponent(oldPath);
			String newName = getLastComponent(newPath);
			
			if(!newParent.isDirectory()) return -ErrorCodes.ENOTDIR();
			
			// Rename is typically atomic on Linux, so systems will use it as a way of doing atomic writes by overriding destination
			try { getPath(newPath).trash(); }
			catch(NoSuchElementException e) { /* destination didn't exist, which is fine */ }
			
			if(!oldParent.equals(newParent))
			{
				newParent.addChild(file);
				oldParent.removeChild(file);
			}
			
			if(!oldName.equals(newName))
			{
				file.setTitle(newName);
			}
			
			return 0;
		}
		catch(NoSuchElementException e)
		{
			return -ErrorCodes.ENOENT();
		}
		catch(IOException e)
		{
			e.printStackTrace();
			return -ErrorCodes.EIO();
		}
	}

	@Override
	public int rmdir(final String path)
	{
		try
		{
			File directory = getPath(path);
			if(!directory.isDirectory()) return -ErrorCodes.ENOTDIR();
			
			if(directory.getParents().size() > 1) getParentPath(path).removeChild(directory);
			else
			{
				// TODO: Decide what to do if parent is in trash, maybe add drive root as parent?
				directory.trash();
			}
			return 0;
		}
		catch(NoSuchElementException e)
		{
			return -ErrorCodes.ENOENT();
		}
		catch(IOException e)
		{
			e.printStackTrace();
			return -ErrorCodes.EIO();
		}
	}

	@Override
	public int truncate(final String path, final long offset)
	{
		// TODO: verify that the file at path is writable, in
		// accordance w/ man pages. Perhaps this is always the case?
		try {
			getPath(path).truncate(offset);
			FileWriteCollector collector = openFiles.get(path);
			if (collector != null) {
				collector.truncate(offset);
			}
		} catch (IOException e) {
			e.printStackTrace();
			return -ErrorCodes.EIO();
		}
		return 0;
	}

	@Override
	public int unlink(final String path)
	{
		try
		{
			File file = getPath(path);
			if(file.getParents().size() > 1) getParentPath(path).removeChild(file);
			else
			{
				// TODO: Decide what to do if parent is in trash, maybe add drive root as parent?
				System.out.println("unlink " + file.getTitle());
				file.trash();
			}
			return 0;
		}
		catch(NoSuchElementException e)
		{
			return -ErrorCodes.ENOENT();
		}
		catch(IOException e)
		{
			e.printStackTrace();
			return -ErrorCodes.EIO();
		}
	}
	
	@Override
	public synchronized int write(final String path, final ByteBuffer buf, final long bufSize, final long writeOffset, final FileInfoWrapper wrapper)
	{
		// Sanity check
		if(wrapper.fh() == 0) throw new Error("File handle for "+path+" should be non-zero");
		
		FileWriteCollector collector = openFiles.get(fileHandles.get(wrapper.fh()));
		try
		{
			if(collector == null)
			{
				collector = new FileWriteCollector(fileHandles.get(wrapper.fh()), path.substring(path.lastIndexOf('/')+1));
				openFiles.put(fileHandles.get(wrapper.fh()), collector);
			}
			
			collector.write(writeOffset, buf, bufSize);
			
			return (int)bufSize;
		}
		catch(NoSuchElementException e)
		{
			try{collector.getFile();}
			catch(Throwable t){t.printStackTrace();}
			openFiles.remove(path);
			return -ErrorCodes.ENOENT();
		}
		catch(IOException e)
		{
			e.printStackTrace();
			try{collector.getFile();}
			catch(Throwable t){t.printStackTrace();}
			openFiles.remove(path);
			return -ErrorCodes.EIO();
		}
	}
	
	@Override
	public synchronized int flush(String path, FileInfoWrapper info)
	{
		File f = fileHandles.get(info.fh());
		if (f == null) {
			return ErrorCodes.EBADF(); // bad fd
		}
		FileWriteCollector collector = openFiles.get(f);
        if(collector == null) {
        	// then there's definitely nothing to flush
            return 0;
        }
		try
		{
			collector.flushCurrentFragmentToDb();
			f.update(false);
			openFiles.remove(f);
			collector.getFile(); // TODO: Getters shouldn't have visible side effects; consider adding a close() or flush() method.
			return 0;
		}
		catch(IOException e)
		{
			e.printStackTrace();
			return -ErrorCodes.EIO();
		}
		catch(RuntimeException e)
		{
			e.printStackTrace();
			return -ErrorCodes.EIO();
		}
	}

	@Override
	public int fsync(String path, int datasync, FileInfoWrapper info)
	{
		return flush(path, info);
	}

	@Override
	public int getxattr(final String path, final String xattr, final XattrFiller filler, final long size, final long position)
	{
		return super.getxattr(path, xattr, filler, size, position);
		/*
		if (!path.equals(filename)) {
			return -ErrorCodes.firstNonNull(ErrorCodes.ENOATTR(), ErrorCodes.ENOATTR(), ErrorCodes.ENODATA());
		}
		if (!helloTxtAttrs.containsKey(xattr)) {
			return -ErrorCodes.firstNonNull(ErrorCodes.ENOATTR(), ErrorCodes.ENOATTR(), ErrorCodes.ENODATA());
		}
		filler.set(helloTxtAttrs.get(xattr));
		return 0;
		*/
	}

	@Override
	public int listxattr(final String path, final XattrListFiller filler)
	{
		return super.listxattr(path, filler);
		/*
		if (!path.equals(filename)) {
			return -ErrorCodes.ENOTSUP();
		}
		filler.add(helloTxtAttrs.keySet());
		return 0;
		*/
	}

	@Override
	public int removexattr(final String path, final String xattr)
	{
		return super.removexattr(path, xattr);
		/*
		if (!path.equals(filename)) {
			return -ErrorCodes.ENOTSUP();
		}
		if (!helloTxtAttrs.containsKey(xattr)) {
			return -ErrorCodes.ENOATTR();
		}
		helloTxtAttrs.remove(xattr);
		return 0;
		*/
	}

	@Override
	public int setxattr(final String path, final String xattrName, final ByteBuffer buf, final long size, final int flags,
			final int position)
	{
		return super.setxattr(path, xattrName, buf, size, flags, position);
		/*
		if (!path.equals(filename)) {
			return -ErrorCodes.ENOTSUP();
		}
		byte[] attr;
		if (!helloTxtAttrs.containsKey(xattr)) {
			attr = new byte[(int) (size + position)];
			helloTxtAttrs.put(xattr, attr);
		}
		else {
			attr = helloTxtAttrs.get(xattr);
			if (attr.length < size + position) {
				attr = Arrays.copyOf(attr, (int) (size + position));
			}
		}
		buf.get(attr, position, (int) size);
		return 0;
		*/
	}
	
	@Override
	public int ftruncate(String path, long offset, FileInfoWrapper info)
	{
		/*
		 * N.B. from man pages:
		 * With  ftruncate(),  the file must be open for writing; with truncate(), the file
       	 * must be writable.
		 */
		if ((info.openMode().getBits() & OpenMode.WRITEONLY.getBits()) > 0) {
			return truncate(path, offset);
		} else {
			return ErrorCodes.EACCES();
		}
	}

	/**
	 * According to fuse documentation, this is always
	 * preceded by a flush().
	 * Ref: http://fuse.sourceforge.net/doxygen/structfuse__operations.html
	 */
	@Override
	public int release(String path, FileInfoWrapper info)
	{
		File f = fileHandles.remove(info.fh());
		if (f == null) {
			return ErrorCodes.EBADF(); // bad fd
		}
		try
		{
			f.close();
		}
		catch(IOException e)
		{
			e.printStackTrace();
			return ErrorCodes.EIO();
		}
		openFiles.remove(f);
		return 0;
	}
	
	@Override
	public void afterUnmount(java.io.File oldMountPoint)
	{
		try
		{
			for (FileWriteCollector collector : openFiles.values()) {
				collector.flushCurrentFragmentToDb();
			}
		}
		catch(IOException e)
		{
			throw new RuntimeException(e);
		}
		catch(RuntimeException e)
		{
			throw new RuntimeException(e);
		}
		
		synchronized(this) { notifyAll(); }
	}

	/**
	 * Hack to support slashes in file names (swap in and out a nearly identical UTF-8 character)
	 * @param s input string
	 * @return s with forward slashes replaced by similar UTF-8 character
	 */
	private static String forwardSlashHack(String s) {
		return s.replaceAll("/", IDENTICAL_FORWARD_SLASH_CHARACTER);
	}
}
