package com.clarisma.common.io;



import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

// concurrent reading is not possible

// TODO: We can safely read as long as we ensure all buffers are mapped
//  Or see Store for perfectly thread-safe solution

// TODO: cleanup needed

/**
 * A PileFile provides disk-based storage for a large number of BLOBs,
 * referred to as Piles. Data can be easily appended to Piles (hence their name:
 * data is "piled on"). The maximum size of an individual Pile depends on the
 * PileFile Page Size, ranging from 8 TB (for 4-KB pages) to virtually unlimited.
 *
 * Piles cannot shrink or be deleted. (For a more flexible approach to storing
 * BLOBs, consider using a {@link com.clarisma.common.store.BlobStore}.
 *
 * Each PileFile can hold a maximum of 64M - 1 (2^26 - 1) Piles.
 * This number is fixed at the time the PileFile is created and cannot
 * subsequently be changed. Piles are numbered sequentially starting with 1.
 *
 * A PileFile is stored as a single sparse file, memory-mapped in 1-GB chunks.
 * The file is organized in Pages, whose size is fixed at creation time and
 * must be a power-of-2 between 4KB and 1GB.
 *
 * # PileFile Format
 *
 * ## Header
 *
 * 0-3		magic (TODO)
 * 4-7		total number of pages in use
 * 8-11     number of piles
 * 12		page size (lowest 5 bits used)
 * 13-15	reserved
 * 16-n		Index Entries for each pile (16 bytes each)
 *
 * ## Index Entry
 *
 * 0-3		First page of Pile
 * 4-7		Last page of Pile
 * 8-15		Gross size of Pile (content plus 4-byte link entry per page)
 *
 * ## Page
 *
 * 0-3		Number of the next Page of the Pile, or 0 if this is the last
 * 4-n		content
 *
 *
 */
public class PileFile extends MappedFile
{
	private final int pileCount;
	private final int pageSize;
	private final int pageSizeAsLog;
	/**
	 * Bit mask we need to apply to a pile's size to obtain the space occupied in the final page.
	 */
	private final int sizeMask;
	/**
	 * Number of bits we need to right-shift a page number in order to obtain the 1-GB mapping
	 * where the page is stored.
	 */
	private final int mappingShift;
	private final ByteBuffer baseMapping;

	private static final int ENTRY_SIZE = 16;
	private static final int OFS_NUMBER_OF_PAGES = 4;
	private static final int OFS_NUMBER_OF_PILES = 8;
	private static final int OFS_PAGESIZE = 12;

	public static PileFile create(Path path, int pileCount, int pageSize) throws IOException
	{
		Files.deleteIfExists(path);
		return new PileFile(path, pileCount, pageSize);
	}

	public static PileFile openExisiting(Path path) throws IOException
	{
		if(!Files.exists(path))
		{
			throw new FileNotFoundException("Pilefile does not exist: " + path);
		}
		return new PileFile(path, 1, 4096);
	}

	private PileFile(Path path, int pileCount, int pageSize) throws IOException
	{
		super(path);
		if(pileCount< 1 || pileCount > ((1 << 26)-1))
		{
			throw new IllegalArgumentException("Pile count must be between 1 and 2^26-1");
		}
		if((pageSize & (pageSize-1)) != 0 || pageSize < 4096 || pageSize > MAPPING_SIZE)
		{
			throw new IllegalArgumentException("Page size must be log-2 between 4K and 1G");
		}
		baseMapping = getMapping(0);
		int flags = baseMapping.getInt(OFS_PAGESIZE);
		if(flags != 0)
		{
			// existing file: take pileCount and pagesize from header

			int pageSizeAsLog = flags;
			this.pageSizeAsLog = pageSizeAsLog;
			this.pageSize = 1 << pageSizeAsLog;
			this.pileCount = baseMapping.getInt(OFS_NUMBER_OF_PILES);
		}
		else
		{
			this.pileCount = pileCount;
			this.pageSize = pageSize;
			pageSizeAsLog = Integer.numberOfTrailingZeros(pageSize);
			baseMapping.putInt(OFS_NUMBER_OF_PILES, pileCount);
			baseMapping.putInt(OFS_PAGESIZE, pageSizeAsLog);
		}
		sizeMask = 0xffff_ffff >>> (32-pageSizeAsLog);
		mappingShift = 30 - pageSizeAsLog;

		if(numberOfPages() == 0)
		{
			int entriesPerPage = pageSize / ENTRY_SIZE;
			int indexPageCount = (pileCount + entriesPerPage) / entriesPerPage;
			setNumberOfPages(indexPageCount);
		}
	}

	public int pileCount()
	{
		return pileCount;
	}
	
	private int numberOfPages()
	{
		return baseMapping.getInt(OFS_NUMBER_OF_PAGES);
	}

	private void setNumberOfPages(int pages)
	{
		baseMapping.putInt(OFS_NUMBER_OF_PAGES, pages);
	}
	
	private int allocPage()
	{
		int page = numberOfPages();
		setNumberOfPages(page+1);
		return page;
	}

	public void append(int pile, byte[] data, int start, int len) throws IOException
	{
		// log.debug("Appending {} bytes to pile #{}", len, pile);
		assert pile > 0 && pile <= pileCount: String.format("Invalid pile: %d", pile);
		// assert baseMapping.limit() == (1 << 30) : String.format("Wrong buffer limit = %d", baseMapping.limit());
		int ptrEntry = pile * ENTRY_SIZE;
		int lastPage = baseMapping.getInt(ptrEntry + 4);
		long pileSize = baseMapping.getLong(ptrEntry + 8);
		if(lastPage == 0)
		{
			lastPage = allocPage();
			pileSize = 4;
			baseMapping.putInt(ptrEntry, lastPage);
			baseMapping.putInt(ptrEntry + 4, lastPage);
		}
		int lastPageUsedBytes = (int)pileSize & sizeMask;
		if(lastPageUsedBytes == 0) lastPageUsedBytes = pageSize;
		int pageSpaceRemaining = pageSize - lastPageUsedBytes;
		ByteBuffer mapping = getMapping(lastPage >> mappingShift);
		int pageOffset = (lastPage << pageSizeAsLog) & 0x3fff_ffff;
		mapping.position(pageOffset + lastPageUsedBytes);
		if(pageSpaceRemaining >= len)
		{
			mapping.put(data, start, len);
		}
		else
		{
			int remainingLen = len;
			for(;;)
			{
				mapping.put(data, start, Math.min(pageSpaceRemaining,remainingLen));
				start += pageSpaceRemaining;
				remainingLen -= pageSpaceRemaining;
				if(remainingLen <= 0) break;
				lastPage = allocPage();
				mapping.putInt(pageOffset, lastPage);
				pageSpaceRemaining = pageSize - 4;
				pileSize += 4;
				mapping = getMapping(lastPage >> mappingShift);
				pageOffset = (lastPage << pageSizeAsLog) & 0x3fff_ffff;
				mapping.position(pageOffset + 4);
			}
			baseMapping.putInt(ptrEntry + 4, lastPage);
		}
		baseMapping.putLong(ptrEntry + 8, pileSize + len);
	}

	public void append(int pile, byte[] data) throws IOException
	{
		append(pile, data, 0, data.length);
	}

	// TODO: this is not threadsafe
	//  Can be made threadsafe starting with JDK 13
	//  but getMapping() is not safe because list of mappings may grow
	// TODO: We can make getMapping() safe
	/*
	public class PileInputStream extends SeekableInputStream
	{
		private ByteBuffer buf;
		private int pos;
		private int currentPage;
		private int nextPage;
		private int lastPageSize;
		private int bytesRemainingInPage;
		
		public PileInputStream(int firstPage, int lastPageSize) throws IOException 
		{
			this.lastPageSize = lastPageSize;
			seekPage(firstPage);
		}

		private void seekPage(int page) throws IOException
		{
			currentPage = page;
			buf = getMapping(page >> mappingShift);
			pos = (page << pageSizeAsLog) & 0x3fff_ffff;
			nextPage = buf.getInt(pos);
			pos += 4;
			bytesRemainingInPage = ((nextPage==0) ? lastPageSize : pageSize) - 4;
		}
		
		public int read() throws IOException 
		{
			if(bytesRemainingInPage == 0)
			{
				if(nextPage==0) return -1;
				seekPage(nextPage);
			}
			bytesRemainingInPage--;
			return buf.get(pos++);
		}
		
		public int read(byte b[], int off, int len) throws IOException 
		{
			if(bytesRemainingInPage >= len)
			{
				buf.position(pos);
				buf.get(b, off, len);
				pos += len;
				bytesRemainingInPage -= len;
				return len;
			}
			if(bytesRemainingInPage==0 && nextPage == 0) return -1;
			
			int bytesRead = 0;
			while(len > 0)
			{
				buf.position(pos);
				int chunkSize = Math.min(bytesRemainingInPage, len);
				buf.get(b, off, chunkSize);
				bytesRead += chunkSize;
				off += chunkSize;
				len -= chunkSize;
				if(nextPage == 0) break;
				seekPage(nextPage);
			}
			return bytesRead;
		}

		public long position() 
		{
			return (((long)currentPage) << 32) | pos;
		}

		public void seek(long loc) throws IOException 
		{
			seekPage((int)(loc >> 32));
			int newPos = (int)loc;
			bytesRemainingInPage -= newPos-pos;
			pos = newPos; 
		}
	}

	public SeekableInputStream read(int pile) throws IOException
	{
		assert pile > 0 && pile <= pileCount;
		int ptrEntry = pile * ENTRY_SIZE;
		int firstPage = baseMapping.getInt(ptrEntry);
		long pileSize = baseMapping.getLong(ptrEntry + 8);
		int lastPageSize = (int)pileSize & sizeMask;
		if (lastPageSize == 0) lastPageSize = pageSize;
		return new PileInputStream(firstPage, lastPageSize);
	}
	*/
	
	public long dataSize(int pile)
	{
		int ptrEntry = pile * ENTRY_SIZE;
		long pileSize = baseMapping.getLong(ptrEntry + 8);
		int numberOfPages = (int)((pileSize+pageSize-1) >> pageSizeAsLog);
		return pileSize - numberOfPages * 4;
	}
	
	// not threadsafe
	// TODO: Since 13, we've got absolute bulk get, so we can make it safe
	//   except that getMapping can grow list of buffers, which is not threadsafe
	public byte[] load(int pile) throws IOException
	{
		assert pile > 0 && pile <= pileCount;
		int ptrEntry = pile * ENTRY_SIZE;
		int page = baseMapping.getInt(ptrEntry);
		if(page==0) return new byte[0];
		long pileSize = baseMapping.getLong(ptrEntry + 8);
		if(pileSize < 0)
		{
			throw new IOException(
				String.format(
					"Corrupt PileFile, Pile %d has an invalid pile size (%d)",
					pile, pileSize));
		}
		int numberOfPages = (int)((pileSize+pageSize-1) >> pageSizeAsLog);
		long longDataSize = pileSize - numberOfPages * 4;
		// TODO: error if oversize
		int dataSize = (int)longDataSize;
		byte[] data = new byte[dataSize];
		int dataPos = 0;
		int dataPerPage = pageSize-4;
		while(page != 0)
		{
			if(dataSize <= 0)
			{
				throw new IOException(
					String.format(
						"Corrupt PileFile, Pile %d has more pages than " +
						"actual data (Pile size = %d, next page = %d)",
						pile, pileSize, page));
			}
			ByteBuffer buf = getMapping(page >> mappingShift);
			int pos = (page << pageSizeAsLog) & 0x3fff_ffff;
			page = buf.getInt(pos);
			buf.position(pos+4);
			buf.get(data, dataPos, Math.min(dataSize, dataPerPage));
			dataPos += dataPerPage;
			dataSize -= dataPerPage;
		}
		return data;
	}
}
