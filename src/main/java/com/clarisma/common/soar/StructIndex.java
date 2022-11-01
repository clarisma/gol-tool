/*
 * Copyright (c) Clarisma / GeoDesk contributors
 *
 * This source code is licensed under the AGPL 3.0 license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.clarisma.common.soar;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * A fast, locality-aware hash table used to retrieve Structs via their primary ID.
 *  
 * @param <T> the type of Struct addressed by this StructIndex
 */

// TODO: index fails if null pointer is put into a Link

public abstract class StructIndex<T extends Struct> extends Struct
{
	private int slotCount;
	private List<SChain> spilloverChains;
	/**
	 * The number of slots that are occupied by an entry that is the first
	 * in its chain. higher is better;
	 */
	private int activeSlotCount;
	/**
	 * The percentage of index slots with more than one entry. Should be as close
	 * to zero as possible, but realistically will be around 0.6
	 */
	private float collisionRatio;
	/**
	 * The percentage of index slots whose entries don't fit in the slot's home page, requiring
	 * a Jump (and hence an additional page retrieval). Should be as close to zero as
	 * possible. 
	 */
	private float jumpRatio;
	/**
	 * The maximum number of entries in any given slot. Lower is better.
	 */
	private int longestChain;			
	/**
	 * The average number of entries per slot. Lower is better.
	 */
	private float averageChainLength;
	/**
	 * The total size of the index table, plus any spillover chains, in bytes.
	 * Lower is better, but this stands in conflict with the above metrics.
	 */
	private int totalIndexSize;		
	private List<T> items;
	private int[] cells;
	
	private static final int LINK = 0;
	private static final int TAIL = 1;
	private static final int JUMP = 2;
	private static final int JUMP_TO_SPILLOVER = 3;
	
	protected static final int TWENTY_BITS = 0x000f_ffff;
	
	private static final int PAGE_SIZE = 4096;
	private static final int SLOT_SIZE = 8;
	private static final int SLOTS_PER_PAGE = PAGE_SIZE / SLOT_SIZE;
	
	public StructIndex(List<T> items, int slotCount)
	{
		spilloverChains = new ArrayList<>();
		if(items == null || items.size() == 0)
		{
			cells = new int[2];
			setSize(8);
			setAlignment(2);
			this.slotCount = 1;
			return;
		}
		this.items = items;
		if(slotCount <= 0)
		{
			slotCount = ((items.size() + SLOTS_PER_PAGE - 1) / SLOTS_PER_PAGE) * SLOTS_PER_PAGE;
		}
		this.slotCount = slotCount;
		IndexEntry<T>[] entries = createTable();
		calculateChainStatistics(entries);
		
		// log.debug("Building index with {} slots", tableSize);
		
		cells = new int[slotCount * 2];
		setSize(slotCount * 8);
		// for best performance, the Index should occupy entire file blocks
		setAlignment(12); // TODO			

		int pageCount = (entries.length + SLOTS_PER_PAGE - 1) / SLOTS_PER_PAGE;
		Page[] pages = new Page[pageCount];
		for(int i=0; i<pageCount; i++) pages[i] = new Page(i);
	
		List<IndexEntry<T>> collisions = new ArrayList<>();
		
		// First, place all collision-free entries and mark unoccupied slots
		
		int emptySlotCount = 0;
		for(int i=0; i<entries.length; i++)
		{
			Page page = pages[i / SLOTS_PER_PAGE];
			int cell = i * 2;
			if(entries[i] == null)
			{
				// The hash slot contains no entries: mark cells as available 
				page.doubleCells.add(cell);
				emptySlotCount++;
				continue;
			}
			if(entries[i].next == null)
			{
				// The hash slot contains a single entry: place it, and mark the second 
				// cell of the slot as available
				putEntry(cell, entries[i]);
				page.singleCells.add(cell + 1);
				continue;
			}
			// The slot contains multiple entries: defer to the next phase 
			collisions.add(entries[i]); 
		}
		
		// log.debug("{} slots have more than one entry", collisions.size());
		// log.debug("{} slots are empty", emptySlotCount);
	
		// Now, try to place collision entries, keeping them in their home page if
		// possible. Start with the longest chain first
		
		List<IndexEntry<T>> farChains = new ArrayList<>();
		
		collisions.sort((a,b) -> Integer.compare(b.depth, a.depth));
		for(IndexEntry<T> chain: collisions) 
		{
			IndexEntry<T> rest = placeLocalChain(chain, pages[chain.slot / SLOTS_PER_PAGE]);
			if(rest != null) farChains.add(rest);
		}
		
		// log.debug("{} chains need to be placed outside their home page", farChains.size());
		
		// Finally, we need to place entries that didn't fit into their home page
		// We'll go through each Page, and see if we can fit any chain (we'll try the 
		// longest chains first)
		
		jumpRatio = (float)farChains.size() / activeSlotCount; 
		
		placeFarChains(pages, farChains);
		
		// Any chains that cannot be placed inside the pages of the index are turned
		// into "spillover" chains

		totalIndexSize = slotCount * SLOT_SIZE;
		
		for(IndexEntry<T> chain: farChains)
		{
			SChain spillover = new SChain(chain);
			assert chain.linkCell < 0;		// must be linked from a Jump
			cells[-chain.linkCell-1] = (spilloverChains.size() << 2) | JUMP_TO_SPILLOVER;
				// since we don't know the location of the SChain yet,
				// we put its index number within spilloverChains 
				// into the Jump cell. We use the JUMP_TO_SPILLOVER lop
				// to differentiate from jumps within the index.
				// The writeTo method (which will know the location) 
				// will turn this into a regular JUMP
			spilloverChains.add(spillover);
			totalIndexSize += spillover.size();
		}
		
		// log.debug("{} chains are spillovers", spilloverChains.size());
				
		// As the final step, we place a Tail for an arbitrary entry into empty index
		// slots, which saves a null check during lookup
		// but likely increases compressed file size, since zeroes would 
		// compress better
		// TODO
	}

	private void calculateChainStatistics(IndexEntry<T>[] entries)
	{
		int totalChainLength = 0;
		int collisionChains = 0;
		for(IndexEntry<T> e: entries)
		{
			if(e==null) continue;
			if(e.depth > longestChain) longestChain = e.depth;
			if(e.next != null) collisionChains++;
			totalChainLength += e.depth + 1;
			activeSlotCount++;
		}
		longestChain++;
		averageChainLength = (float)totalChainLength / activeSlotCount;
		collisionRatio = (float)collisionChains / activeSlotCount;
	}
	
	public int tablesize()
	{
		return slotCount;
	}

	public int itemCount()
	{
		return items==null? 0 : items.size();
	}
	
	public List<SChain> spilloverChains()
	{
		return spilloverChains;
	}
	
	private void placeFarChains(Page[] pages, List<IndexEntry<T>> farChains)
	{
		farChains.sort((a,b) -> Integer.compare(b.depth, a.depth));
		
		for(Page page: pages)
		{
			int n = 0;
			int pageCapacity = page.entryCapacity();
			while(n < farChains.size())
			{
				IndexEntry<T> chain = farChains.get(n);
				if(chain.depth < pageCapacity)		// chain with 3 entries has depth=2
				{
					// The chain can be fully placed within the page
					int cell;
					IndexEntry<T> entry = chain;
					while(entry != null)
					{
						int linkCell = chain.linkCell;
						if(linkCell < 0) linkCell = -linkCell-1;
						if(entry.next != null)
						{
							cell = page.findDoubleCell(linkCell);
						}
						else
						{
							cell = page.findSingleCell(linkCell);
						}
						assert cell >= 0;
						putEntry(cell, entry);
						entry = entry.next;
					}
					pageCapacity = page.entryCapacity();
					farChains.remove(n);
					continue;
				}
				n++;
			}
		}
	}
	
	/**
	 * Attempts to place as many index entries in the given chain into the page 
	 * where their hash slot is located.  
	 * 
	 * @param entry 	The first entry of the chain to be placed.
	 * @param page		The page where the entries should be placed 
	 * @return			The first entry of the remaining entries that could not
	 * 					be placed because we ran out of cells, or <null> if all
	 * 					were placed.
	 */
	private IndexEntry<T> placeLocalChain(IndexEntry<T> entry, Page page)
	{
		assert entry != null && entry.next != null;
		
		for(;;)
		{
			int linkCell = entry.linkCell;
			assert linkCell >= 0;
				// If linkCell is negative, this means the entry
				// is reached via a Jump, which cannot be the case
				// for local chains
			// if(linkCell < 0) linkCell = -linkCell-1; 
			
			if(entry.next == null)
			{
				// If this entry is the last, we only need to place a Tail
				
				assert entry.linkCell != 0;
				int cell = page.findSingleCell(linkCell);
				assert cell >= 0;
				putEntry(cell, entry);
				return null;
			}
			else  
			{
				// More entries follow this one
				
				boolean isFirst = entry.linkCell == 0;
				boolean isPageFull = isFirst ?
					page.singleCells.isEmpty() && page.doubleCells.isEmpty() :
					(page.doubleCells.isEmpty() || page.singleCells.isEmpty()) &&
					page.doubleCells.size() < 2;
				int cell;
					
				// In order to place an entry, we need at least one double slot 
				// (for the entry's Link) and one single slot (for a Tail or Jump);
				// alternatively, two double slots will also work, since we can 
				// convert a double to two singles
				// If the entry is the first, we just need a single slot
				// (the slot's double cell is always available) 
				
				if(isPageFull) 
				{
					// No room on this page; must place a Jump
				
					/*
					log.debug("Page {} is full ({} singles, {} doubles)",
						page.number, page.singleCells.size(), page.doubleCells.size());
					*/
					
					if(isFirst)
					{
						cell = entry.slot * 2; 
						// The Jump only consumes the first cell of the slot,
						// so we can mark the second cell as available
						page.singleCells.add(cell + 1);
						/*
						log.debug("Therefore, we place a Jump for the first entry in slot {} in cell {}",
							entry.slot, cell);
						log.debug("Page {} now has {} singles and {} doubles",
							page.number, page.singleCells.size(), page.doubleCells.size());
						*/
					}
					else
					{
						cell = page.findSingleCell(linkCell);
						putLink(entry.linkCell, cell);
						/*
						log.debug("Therefore, we place a Jump for the middle entry in (slot {}) in cell {}",
								entry.slot, cell);
						log.debug("Page {} now has {} singles and {} doubles",
							page.number, page.singleCells.size(), page.doubleCells.size());
						*/
					}
					entry.linkCell = -cell-1;
					return entry;
				}
				if(isFirst)
				{
					cell = entry.slot * 2;
				}
				else
				{
					cell = page.findDoubleCell(linkCell);
				}
				putEntry(cell, entry);
			}
			entry = entry.next;
		}
	}
	
	private void verifyNotLink(int cell)
	{
		if(cell < 0 || cell % 2 != 0) return;
		int v = cells[cell];
		int lopType = v & 3;
		assert v==0 || lopType==JUMP || lopType==TAIL :	"Cell is a link";
	}
	
	
	private void putEntry(int cell, IndexEntry<T> entry)
	{
		assert cell >= 0;
		assert cells[cell] == 0: "Cell has already been filled"; 
		if(entry.next==null) 
		{
			// This is the last entry in a chain: tag pointer as Tail
			verifyNotLink(cell-1);
			cells[cell] = (entry.itemNumber << 2) | TAIL;
		}
		else
		{
			// This is a Link: 
			assert cells[cell+1] == 0: "Cell has already been filled";
			assert cell % 2 == 0: "Must be placed at even cell";
			cells[cell] = entry.itemNumber << 2;
			cells[cell+1] = keySample(entry.item) & TWENTY_BITS;
			entry.next.linkCell = cell+1;
		}
		if(entry.linkCell != 0) putLink(entry.linkCell, cell);
	}
	
	/**
	 * Places a link to complete a lookup chain. 
	 *   
	 * @param fromCell the cell where to place a link 
	 * 		If positive: must be the second cell of a Link (therefore, must be odd)
	 * 		If negative: the cell represents a Jump (-1 = 0, -2 = 1, etc.)   
	 * @param toCell
	 */
	private void putLink(int fromCell, int toCell)
	{
		assert fromCell != 0;
		if(fromCell < 0)
		{
			fromCell = -fromCell-1;
			assert fromCell >= 0 && fromCell < slotCount * 2;
			cells[fromCell] = ((toCell - fromCell) * 4) | JUMP;
			return;
		}
		assert fromCell >= 0 && fromCell < slotCount * 2;
		assert fromCell % 2 == 1: "Link cell must be odd";
		int existingBits = cells[fromCell]; 
		assert (existingBits & 0xfff0_0000) == 0;
		int delta = toCell-fromCell;
		assert delta >= -2048 && delta < 2048: "Link exceeds range";
		cells[fromCell] = (delta << 20) | existingBits;
	}
	
	private IndexEntry<T>[] createTable()
	{
		@SuppressWarnings("unchecked")
		IndexEntry<T>[] entries = new IndexEntry[slotCount];
		
		for(int i=0; i<items.size(); i++)
		{
			T item = items.get(i);
			IndexEntry<T> entry = new IndexEntry<>();
			entry.item = item;
			entry.itemNumber = i+1;
			int index = (hash(item) & 0x7fffffff) % slotCount;
			entry.slot = index;
			if(entries[index] != null) 
			{
				entry.next = entries[index];
				entry.depth = entry.next.depth + 1;
			}
			entries[index] = entry;
		}
		return entries;
	}

	private static class IndexEntry<T>
	{
		T item;
		/**
		 * 1-based index of item in `items`
		 */
		int itemNumber;
		/**
		 * The next IndexEntry that belongs to the same slot,
		 * or null if this is the last (or only) entry
		 */
		IndexEntry<T> next;
		/**
		 * The number of the slot where this entry is indexed.
		 * This is not necessarily where this entry will be
		 * placed. An IndexEntry will only be placed in its
		 * slot if it is a) the only entry that hashed to this 
		 * slot, or b) it is the first of multiple entries,
		 * and there is room in the same page to place at least
		 * the next entry (otherwise, a Jump will be placed in 
		 * the first cell of the slot, which refers to another
		 * location where the entry will be placed).  
		 */
		int slot;
		/**
		 * The cell where a link (second Cell of Link, or Jump) needs to be placed
		 * to reach this entry. 
		 * If positive: second Cell of Link (must be odd)
		 * If negative: Jump (negate, subtract 1: -1 means Cell 0, -2 = Cell 1)
		 * If 0: this is the first item in a chain, and it is placed in its slot
		 */
		int linkCell = 0;		
		/**
		 * How many entries follow this entry in the chain (0 means this is the last)
		 */
		int depth;
	}
	
	private static class Page
	{
		int number;
		TreeSet<Integer> doubleCells = new TreeSet<>();
		TreeSet<Integer> singleCells = new TreeSet<>();
		
		public Page(int number)
		{
			this.number = number;
		}
		/**
		 * Allocates a free cell, whose next neighboring cell is also free
		 * (i.e. an unoccupied slot), which is closest to the specified cell. 
		 * 
		 * @param closestTo		  	
		 * @return a cell index (always even), or -1 if this page has no more empty slots
		 */
		public int findDoubleCell(int closestTo)
		{
			assert closestTo >= 0;
			int cell;
			if(doubleCells.isEmpty()) return -1;
			Integer lower = doubleCells.lower(closestTo);
			Integer upper = doubleCells.higher(closestTo);
			if(lower==null)
			{
				cell = upper;
			}
			else if (upper==null)
			{
				cell = lower;
			}
			else
			{
				cell = (closestTo-lower < upper-closestTo) ? lower : upper;
			}
			doubleCells.remove(cell);
			return cell;
		}
		
		/**
		 * Allocates a free cell that is closest to the specified cell.
		 *  
		 * @param closestTo
		 * @return the cell index, or -1 if the page has no more free cells. 
		 */
		public int findSingleCell(int closestTo)
		{
			assert closestTo >= 0;
			int cell;
			if(singleCells.isEmpty())
			{
				// If all individual cells are taken, we attempt to allocate 
				// a double cell (the extra cell is added to the free individual cells)
				cell = findDoubleCell(closestTo);
				if(cell >= 0) singleCells.add(cell+1);
				return cell;
			}
			Integer lower = singleCells.lower(closestTo);
			Integer upper = singleCells.higher(closestTo);
			if(lower==null)
			{
				cell = upper;
			}
			else if (upper==null)
			{
				cell = lower;
			}
			else
			{
				cell = (closestTo-lower < upper-closestTo) ? lower : upper;
			}
			singleCells.remove(cell);
			return cell;
		}
		
		public int entryCapacity()
		{
			int capacity = doubleCells.size();
			return singleCells.isEmpty() ? capacity : (capacity+1);
		}
	}

	
	/**
	 * A struct that represents a "spillover" index chain. It always consists
	 * of one or more Links, followed by a Tail. 
	 */
	private class SChain extends Struct
	{
		private int entryCount;
		private IndexEntry<T> chain;
		
		public SChain(IndexEntry<T> chain)
		{
			this.chain = chain;
			assert chain.depth > 0: "Chain with only one entry, something went wrong";
			// ... because we could have just placed a Tail, instead of a Jump to a Chain
			
			entryCount = chain.depth + 1;
			setSize((chain.depth) * 8 + 4);
			setAlignment(2);
		}
		
		public void writeTo(StructOutputStream out) throws IOException 
		{
			IndexEntry<T> entry = chain;
			for(;;)
			{
				if(entry.next==null) 
				{
					// write the tail
					out.writePointer(entry.item, TAIL);
					break;
				}
				// write a link
				out.writePointer(entry.item);
				out.writeInt((keySample(entry.item) & TWENTY_BITS) | (1 << 20));
					// Top 12 bits contain link to next cell; for spillover chains,
					// the next cell is always literally the next cell (+1)
				entry = entry.next;
			}
		}
		
		public String toString()
		{
			return String.format("INDEX_CHAIN with %d entries", entryCount);
		}
	}
	
	protected int hash(T item)
	{
		int h;
		return (h = item.hashCode()) ^ (h >>> 16);
	}
	
	/**
	 * Returns a 20-bit sample for the given key.
	 * 
	 * @param item
	 * @return
	 */
	protected abstract int keySample(T item);

	public void writeTo(StructOutputStream out) throws IOException 
	{
		int cell = 0;
		while(cell < cells.length)
		{
			int v = cells[cell];
			int lopType = v & 3;
			switch(lopType)
			{
			case LINK:	// (or blank cell)
				if(v == 0)
				{
					out.writeInt(0);
				}
				else
				{
					out.writePointer(items.get((v >>> 2) - 1));
					cell++;
					out.writeInt(cells[cell]);
				}
				break;
			case TAIL:
				out.writePointer(items.get((v >>> 2) - 1), TAIL);
				break;
			case JUMP:
				out.writeInt(v);
					// No need to resolve pointers for a regular Jump,
					// since it is simply a relative address within
					// the index block
				break;
			case JUMP_TO_SPILLOVER:
				out.writePointer(spilloverChains.get(v >>> 2), JUMP);
					// unlike items, this index is 0-based
					// Jump to Spillover becomes a regular Jump
					// now that we've resolved the pointer to the SChain
				break;
			}
			cell++;
		}
	}
	
	
	public String dumped()
	{
		return String.format("INDEX with %d slots", slotCount);
	}
	
	private boolean dumpLop(PrintWriter out, int cell)
	{
		int v = cells[cell];
		if(v == 0) 
		{
			if((cell & 1) == 0)
			{
				int siblingCellValue = cells[cell+1];
				if(siblingCellValue != 0)
				{
					out.format("Null Link (Sibling: %08X)", siblingCellValue);
					return false;
				}
			}
			out.print("empty");
			return true;
		}
		int linkedItemNumber = (v & 0xffff_fffc) >>> 2;
			// TODO: not always the case; actual pointer for jump
		String lopType;
		String link = "";
		String target = null;
		switch(v & 3)
		{
		case 0:		
			lopType = "Link";	
			int next = cells[cell+1] >> 20;
			int p = (cell+1+next) * 4;
			target = linkedItemNumber==0 ? "<null>" : items.get(linkedItemNumber-1).toString();
			link = String.format(" (Next: %d -> %08X)", next, location() + p);  
			break;
		case JUMP:	lopType = "Jump";	
			target = String.format("%08X", location() + cell * 4 + (linkedItemNumber << 2));
			break;
		case TAIL:	lopType = "Tail";	
			target = linkedItemNumber==0 ? "<null>" : items.get(linkedItemNumber-1).toString();
			break;
		default:	lopType = "Invalid";
		}
		out.format("%s to %s%s", lopType, target, link);
		return (v & 3) != 0;
	}
	
	private void dumpSlots(PrintWriter out)
	{
		int loc = location();
		for(int i=0; i<slotCount; i++)
		{
			out.format("  %08X - Slot %d: ", loc + i*8, i);
			if(dumpLop(out, i*2))
			{
				out.print(" / ");
				dumpLop(out, i*2+1);
			}
			out.println();
		}
	}
	
	private void dumpStats(PrintWriter out)
	{
		out.format("  Number of items: %d\n", items==null? 0 : items.size());
		out.format("  Number of slots: %d\n", slotCount);
		out.format("  Collision ratio: %f\n", collisionRatio);
		out.format("  Jump ratio:      %f\n", jumpRatio);
		out.format("  Longest chain:   %d\n", longestChain);
		out.format("  Average chain:   %f\n", averageChainLength);
		out.format("  Spillovers:      %d\n", spilloverChains==null ? 0 : spilloverChains.size());
		out.format("  Total size:      %d\n", totalIndexSize);
	}
	
	public void dump(PrintWriter out)
	{
		super.dump(out); 
		dumpSlots(out);
		dumpStats(out);
	}
}
