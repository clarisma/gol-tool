package com.clarisma.common.io;

import static java.nio.file.StandardOpenOption.READ;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileUtils 
{
	// TODO: works only for files up to 2 GB
	/*
	public static boolean fileContentEquals(Path path1, Path path2) throws IOException
	{
		long size = Files.size(path1);
		if(size != Files.size(path2)) return false;
		FileChannel channel1 = (FileChannel)Files.newByteChannel(path1, READ);
		FileChannel channel2 = (FileChannel)Files.newByteChannel(path2, READ);
		ByteBuffer buf1 = channel1.map(MapMode.READ_ONLY, 0, size); 
		ByteBuffer buf2 = channel2.map(MapMode.READ_ONLY, 0, size);
		channel1.close();
		channel2.close();
		return buf1.mismatch(buf2) < 0;
	}
	*/

	public static boolean fileContentEquals(Path path1, Path path2) throws IOException
	{
		if(Files.size(path1) != Files.size(path2)) return false;
		return Files.mismatch(path1, path2) < 0;
	}

	public static Path addExtension(Path path, String ext)
	{
		if(!ext.startsWith(".")) ext = "." + ext;
		return path.getParent().resolve(path.getFileName() + ext);
	}

	public static String replaceExtension(String path, String ext)
	{
		if(!ext.startsWith(".")) ext = "." + ext;
		int n = indexOfExtension(path);
		return (n < 0 ? path : path.substring(0,n)) + ext;
	}

	private static int indexOfExtension(String path)
	{
		int lastDot = path.lastIndexOf('.');
		int lastSeparator = path.lastIndexOf(File.separatorChar);
		return lastDot > lastSeparator ? lastDot : -1;
	}

	public static String getExtension(String path)
	{
		int n = indexOfExtension(path);
		return n < 0 ? "" : path.substring(n+1);
	}

	public static String pathWithDefaultExtension(String path, String defaultExt)
	{
		String ext = getExtension(path);
		if(!ext.isEmpty()) return path;
		return defaultExt.startsWith(".") ? path + defaultExt : path+'.'+defaultExt;
	}
	
	/**
	 * Replaces a file with a newer version. If the new file has the same content, the new file is
	 * deleted and no further action is taken. Otherwise, if a path is specified for `oldPath`, 
	 * the old file is replaced by the new file. Finally, the current file is replaced by the new
	 * file.
	 *     
	 * @param path			path of the current version of the file
	 * @param oldPath		path of the previous version (may be null)
	 * @param newPath		path of the new version
	 * @return 	true if the file was replaced by a newer version, or false if the contents
	 * 			of the new file were identical 
	 * @throws IOException
	 */
	public static boolean replace(Path path, Path oldPath, Path newPath) throws IOException
	{
		if(FileUtils.fileContentEquals(path, newPath))
		{
			Files.delete(newPath);
			return false;
		}
		if(oldPath != null)
		{
			Files.deleteIfExists(oldPath);
			Files.move(path, oldPath);
		}
		else
		{
			Files.delete(path);
		}
		Files.move(newPath, path);
		return true;
	}

	/**
	 * If a file has the same contents as another file, replaces it with a
	 * hard link.
	 *
	 * @param possibleCopy	the file to check and possibly replace
	 * @param original		the file to compare against
	 * @return true 		if possibleCopy has been substituted with a link,
	 * 						otherwise false
	 * @throws IOException	if the paths don't refer to files, the files don't
	 * 						exist, or operation fails due to lack of permissions
	 */
	public static boolean deduplicate(Path possibleCopy, Path original) throws IOException
	{
		if(!fileContentEquals(possibleCopy, original)) return false;
		Files.delete(possibleCopy);
		Files.createLink(possibleCopy, original);
		return true;
	}
}
