package mets_r.data;


import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;

import mets_r.GlobalVariables;


/**
 * 
 * This data consumer writes the contents of the simulation output buffer
 * to disk in the CSV format.  If no file is specified, the output will
 * be written to the same location as this program with a default name
 * of the format "EvacSim_Output_<timestamp>.csv".  If this is the case,
 * a new filename with an updated timestamp will be used for each restart 
 * of the writing process.
 * 
 * The first field of each line is the simulation tick to which the data
 * of the line pertains, and the second field of each line is the type of
 * data represented by that line.  Each additional field from the third
 * column to the end of the line is specific to the type of data being
 * represented by that line.
 * 
 * @author Christopher Thompson (thompscs@purdue.edu)
 * @version 1.0
 * @date 10 August 2017
 */
public class CsvOutputWriter implements DataConsumer {
    
    /** Whether or not existing output files should be appended. */
    private boolean append;
    
    /** Whether or not to generate a unique filename with each execution. */
    private boolean defaultFilenames;
    
    /** The file to which output data will be written. */
    private File file;
    
    /** The writer which streams prepared CSV content to the output file. */
    private BufferedWriter writer;
    
    /** The number of lines written to the current CSV file. */
    private int linesWritten;
    
    /** The number in the output series for the current CSV file.  */
    private int fileSeriesNumber;
    
    /** The thread which periodically reads the buffer for data to process. */
    private Thread writingThread;
    
    /** The simulation tick currently being processed (or just processed). */
    protected double currentTick;
    
    /** Whether or not the output writer is currently consuming data. */
    private boolean consuming;
    
    /** Whether or not the output writer is paused for consuming data. */
    private boolean paused;
        
    
    /**
     * Creates the CSV output writer object using a default file name and
     * not appending the contents to 
     */
    public CsvOutputWriter() {
        this(new File(CsvOutputWriter.createDefaultFilePath()), false);
    }
    
    
    /**
     * Creates the CSV output writer object to place output from the
     * simulation buffer into the file specified.
     * 
     * @param file the file to which output will be written.
     * @param append whether or not to append to an existing file.
     */
    public CsvOutputWriter(File file, boolean append) {
        // Set whether or not we are to append to existing files
        this.append = append;
        
        // Set the file to which output is written or set the flag
        // so we know to generate a unique filename with each start
        // if a filename was not explicitly given
        this.file = file;
        if (file == null) {
            this.defaultFilenames = true;
        }
        else {
            this.defaultFilenames = false;
        }
        
        // Set the initial state of the writer
        this.consuming = false;
        this.paused = false;
        this.currentTick = -1.0;
        this.writer = null;
        this.writingThread = null;
        this.fileSeriesNumber = 1;
        this.linesWritten = 0;
        
        DataCollector.printDebug("CSV", "FILE: " + this.file.getAbsolutePath());
    }
    
    
    /**
     * Returns whether or not the writer will append to existing files.
     * 
     * @return whether or not the writer will append to existing files.
     */
    public boolean isAppending() {
        return this.append;
    }
    
    
    /**
     * Sets whether or not the writer will append to existing files.
     * 
     * @param append whether or not to append new output to existing files.
     */
    public void setAppending(boolean append) {
        this.append = append;
    }
    
    
    /**
     * Returns the file to which output is written.
     * 
     * @return the file to which output is written.
     */
    public File getOutputFile() {
        return this.file;
    }
    
    
    /**
     * Sets the file to which contents will be written.  If the writer
     * is currently writing, the file cannot be changed and this method
     * will throw an IOException.
     * 
     * @param file the file to which output will be written.
     * @throws IOException if the output file cannot be set.
     */
    public void setOutputfile(File file) throws IOException {
        if (this.consuming || this.paused) {
            throw new IOException("Cannot change file while running.");
        }

        // set the output file to the one given 
        this.file = file;
        this.fileSeriesNumber = 1;
        this.linesWritten = 0;
        
        // check if we are now to use a default filename or not 
        if (this.file == null) {
            // the explicit filename was removed, so we will start generating
            // a unique filename each time the writer starts, using timestamps
            this.defaultFilenames = true;
        }
        else {
            // a specific file has been set, so we will use this each time the
            // writer starts and stops until we are told to use something else
            this.defaultFilenames = false;
        }
    }
    
    
    /**
     * Starts consuming data from the data buffer.  This opens the set
     * output file for writing and creates the thread which will monitor
     * the data buffer for new items to process and write to disk.  Calling
     * this method if the writer is already consuming data will do nothing
     * but resume the consumer if it is currently paused.  If no output
     * file has been explicitly set, a unique filename will be generated when
     * the writer starts which includes a timestamp of the time of start.
     * 
     * @throws Throwable if any problem occurred starting the data writing.
     */
    @Override
    public void startConsumer() throws Throwable {        
        if (this.consuming) {
            // The consumer is already running, so just resume if necessary
            if (this.paused) {
                this.paused = false;
            }
            return;
        }

        // Set the flags to the running state
        this.consuming = true;
        this.paused = false;
        
        // Create a new, unique filename if one has not been set
        if (this.defaultFilenames || this.file == null) {
            this.file = new File(CsvOutputWriter.createDefaultFilePath());
        }
        
        // Create the data writer
        this.openOutputFileWriter();
        
        // Start the data consumption thread
        Runnable writingRunnable = new Runnable() {
            @Override
            public void run() {
                // Get a reference to the data buffer for pulling new items
                DataCollector collector = DataCollector.getInstance();

                // Loop and process data buffers until we are told to stop
                int totalCount = 0;
                int writeCount = 0;
                boolean running = true;
                while (running) {
                    // Check if we are supposed to be done
                    if (!CsvOutputWriter.this.consuming) {
                        DataCollector.printDebug("CSV", "NOT CONSUMING");
                        break;
                    }
                    
                    // Check if we are supposed to be paused
                    if (CsvOutputWriter.this.paused) {
                        DataCollector.printDebug("CSV", "PAUSED");
                        // We are currently paused, so we will wait our delay
                        // before performing another poll on our running state
                        try {
                            Thread.sleep(GlobalVariables.CSV_BUFFER_REFRESH);
                            continue;
                        }
                        catch (InterruptedException ie) {
                            // we've been told to stop running
                            break;
                        }
                    }
                    
                    // Get the next item from the buffer. HGehlot: I have changed from 1 to GlobalVariables.FREQ_RECORD_VEH_SNAPSHOT_FORVIZ to only send the data at the new frequency for viz interpolation
                    double nextTick = CsvOutputWriter.this.currentTick + GlobalVariables.FREQ_RECORD_VEH_SNAPSHOT_FORVIZ;
                    TickSnapshot snapshot = collector.getNextTick(nextTick);
                    if (snapshot == null) {
                        // The buffer has no more items for us at this time
                        if (writeCount > 0) {
                            String report = "Wrote " + writeCount + 
                                " ticks to disk (" + totalCount + " total)";
                            DataCollector.printDebug("CSV", report);
                            writeCount = 0;
                        }
                        
                        // Is the data collection process finished?
                        if (!collector.isCollecting() &&
                            !collector.isPaused()) {
                            // the collector is stopped so no more are coming
                            break;
                        }
                        // We will wait for our longer "the buffer is empty"
                        // delay to give it a chance to add a few new data
                        // items before we loop around and try again...
                        try {
                            Thread.sleep(GlobalVariables.CSV_BUFFER_REFRESH);
                            continue;
                        }
                        catch (InterruptedException ie) {
                            // The thread has been told to stop writing data
                            break;
                        }
                    }
                    
                    // Update the currently processing tick index to this item
                    CsvOutputWriter.this.currentTick = 
                            snapshot.getTickNumber();
                    
                    // Process the current item into lines in the output file
                    try {
                        CsvOutputWriter.this.writeTickSnapshot(snapshot);
                        totalCount++;
                        writeCount++;
                    }
                    catch (IOException ioe) {
                        String errMsg = "WRITE ERROR: " + ioe.getMessage();
                        DataCollector.printDebug("CSV" + errMsg);
                    }
                    
                    // Wait a short delay (a few ms) to give java's thread
                    // scheduler a chance to switch contexts if necessary
                    // before we loop around and grab the next buffer item
                    try {
                        Thread.sleep(5);
                    }
                    catch (InterruptedException ie) {
                        // The thread has been told to stop wrting data
                        break;
                    }
                }
                
                // We have finished collecting data, so we will close the file
                try {
                    CsvOutputWriter.this.closeOutputFileWriter();
                }
                catch (IOException ioe) {
                    // Handle not being able to close the output file?
                }
                
                // Set the data consumption flags as finished
                CsvOutputWriter.this.paused = false;
                CsvOutputWriter.this.consuming = false;
            }
        };
        this.writingThread = new Thread(writingRunnable);
        this.writingThread.start();
    }
    
    
    /**
     * Stops the data consumption and writing (after finishing any currently
     * processing item) and waits for the writing thread to halt.  Stopping
     * a data consumer should only be done if immediate halting of its own
     * operations is desired.  If the simulation is simply stopping, it is
     * best to allow data consumers to run to completion to make sure they
     * have finished processing any remaining items in the buffer.  This will
     * block until the writing thread has finished running and as such should
     * be called from its own thread so as to not block the entire simulation.
     * 
     * @throws Throwable if any problems occurred stopping the data writer.
     */
    @Override
    public void stopConsumer() throws Throwable {
        if (!this.consuming) {
            return;
        }
        
        // Set the flags to the stopped state
        this.paused = false;
        this.consuming = false;
        
        // Wait for the writer thread to halt.  setting the flags above should
        // tell the thread to stop processing new items and return on its own,
        // but we will go ahead and explicitly interrupt the thread to speed
        // this process along, as well.  then we will wait for it to complete.
        this.writingThread.interrupt();
        this.writingThread.join();
        this.writingThread = null;
        
        // Reset the counter to the the initial position
        this.currentTick = -1;
        
        // Dispose of anything we no longer need (like the writer)
        this.closeOutputFileWriter();
        this.writer = null;
    }
    
    
    /**
     * Signals the pausing of consumption of new items from the buffer.
     * Any item currently being written will finish, and the thread which
     * performs the actual pull of data from the buffer will check this
     * state before grabbing the next tick snapshot to know to stop.  The
     * thread is left running as this is only the paused state so it is
     * expected to resume at some point in the future.
     * 
     * @throws Throwable if any error occurs preventing the pause.
     */
    @Override
    public void pauseConsumer() throws Throwable {
        // Check that we are even running
        if (!this.consuming) {
            return;
        }
        
        // set the flags to tell the consumer to stop consuming new items
        this.paused = true;
        this.consuming = true;
        
        // We do nothing to the thread or file writer directly here.  
        // the thread on its next loop will see the paused state and 
        // start checking (with a delay) for this state to change back 
        // to normal running before resuming its work.
    }
    
    
    /**
     * Stops the consumption of new items from the buffer, stops the file
     * writing after the current item has processed, and resets the file
     * writer to start again fresh in the future.  This will block and wait
     * for the writing thread to complete its current task, so it should not
     * be called from the main program thread or the whole simulation could
     * be blocked temporarily. 
     * 
     * @throws Throwable if a problem occurred restarting the file writer.
     */
    @Override
    public void resetConsumer() throws Throwable {
        // Stop the file writer if it is currently operating
        this.stopConsumer();
        
        // Reset the flags to the initial state
        this.paused = false;
        this.consuming = false;
        
        // Reset the current tick counter back to the start
        this.currentTick = -1;
    }
    

    /**
     * Returns the current tick being processed (or just processed).
     * 
     * @return the current tick being processed (or just processed).
     */
    @Override
    public double getTick() {
        return this.currentTick;
    }
    
    
    /**
     * Sets the next tick to process from the data buffer.  The next
     * tick retrieved from the data buffer will be the first found to
     * have a value equal to or greater than this value.  If the model
     * has not yet reached this tick index value, nothing will be
     * returned until the simulation has advanced to this point.
     * 
     * @param tick the next tick to process from the data buffer.
     */
    @Override
    public void setTick(double tick) throws Throwable {
        this.currentTick = tick;        
    }
    
    
    /**
     * Creates a buffered writer for saving the CSV lines to disk and
     * opens it.  Any problem doing so will throw an IOException. 
     * 
     * @throws IOException if the file could not be opened for writing.
     */
    private void openOutputFileWriter() throws IOException {
        // Check the writer doesn't already exist
        if (this.writer != null) {
            // Is the writer still open?
            try {
                this.writer.flush();
                
                // There's no way to get the path to the file the current
                // writer object is using, so we have no choice but to
                // throw an error since we can't check it's the same file
                throw new Exception();
                
            }
            catch (IOException ioe) {
                // If the flush threw an exception, the writer is closed
                // and is just a stale object that wasn't destroyed.
                // we should be safe to replace it.
            }
            catch (Exception e) {
                // Because we're already trapping IOException from the flush,
                // we had to do the awkward step of throwing something else
                // to get out of the try so we can throw a new IOException...
                throw new IOException("CSV writer already has a file open.");
            }
        }
        
        // Check the output file has been given
        if (this.file == null) {
            throw new IOException("No output file has been specified.");
        }
        
        // Create the buffered writer for the file
        FileWriter fw = new FileWriter(this.file);
        this.writer = new BufferedWriter(fw);
    }
    
    
    /**
     * Closes the buffered writer for the output file.
     * 
     * @throws IOException if the output file could not be closed.
     */
    private void closeOutputFileWriter() throws IOException {
        // Check the writer object exists
        if (this.writer == null) {
            return;
        }
        
        // Close the file writer
        this.writer.close();
        this.writer = null;
        
        // If this was a default filename, it is intended for a single
        // use and should be thrown away once it is complete so we cannot
        // accidentally write to it again
        if (this.defaultFilenames) {
            this.file = null;
        }
    }
    
    
    /**
     * Closes the current output file and opens the next in the series of 
     * output files for this simulation execution.  The filename will be the 
     * same with the increment of the series counter.
     * 
     * @throws IOException if the new file could not be created and opened.
     */
    private void startNextOutputFile() throws IOException {
        if (this.file == null ) {
            // There is no file currently open to increment!
            // TODO: figure out how to deal with this situation...
            return;
        }
        
        // Determine the current filename being written
        String filename = this.file.getName();
        if (filename == null || filename.trim().length() < 1) {
            // The filename is null (is this even possible?) or is just
            // whitespace with no valid characters!
            return;
        }
        
        // If we are using default filenames, we know for certain the format
        // of the series.  it should end ".1.csv", ".2.csv", etc.  we can
        // easily create the next in the series this way.  if not, we will
        // have to do a little extra checking to setup the next file.
        String currentEnd = "." + this.fileSeriesNumber + "." + 
                            GlobalVariables.CSV_DEFAULT_EXTENSION;
        String nextEnd = "." + (this.fileSeriesNumber + 1) + "." +
                         GlobalVariables.CSV_DEFAULT_EXTENSION;
        
        String newFilename = filename;
        if (newFilename.endsWith(currentEnd)) {
            // The user is using a standard format filename so easy to update
            newFilename = newFilename.replaceAll(currentEnd + "$", nextEnd);
        }
        else {
            // The user is using a custom filename format so we need to
            // do a little extra work to create the next filename.
            String extEnd = "." + GlobalVariables.CSV_DEFAULT_EXTENSION;
            if (newFilename.endsWith(extEnd)) {
                newFilename = newFilename.replaceAll(extEnd + "$", nextEnd);
            }
            else {
                // TODO: continue checking for other filename variants?
            }
        }
        
        // Create the next filename in this output file series
        File nextFile = new File(this.file.getParent(), newFilename);
        
        // Close out the current output file
        this.closeOutputFileWriter();
        
        // Open the new output file for writing
        this.file = nextFile;
        FileWriter fw = new FileWriter(this.file);
        this.writer = new BufferedWriter(fw);
        
        // Finally, having successfully moved to the next file, update counter
        this.fileSeriesNumber++;
    }
    
    
    /**
     * Writes the given tick snapshot to the output file after converting
     * it to a series of CSV lines.  The buffered writer is flushed at the
     * end so any cached content is immediately written out to disk.
     * 
     * @param tick the tick snapshot to be written to the output file.
     * @throws IOException if any error occurred writing the lines to disk.
     */
    private void writeTickSnapshot(TickSnapshot tick) throws IOException {
        if (tick == null) {
            return;
        }
        
        // Get the csv representation of this tick
        String[] lines = CsvOutputWriter.createTickLines(tick);
        if (lines == null || lines.length < 1) {
            // there was no csv output created by this tick
            return;
        }
        
        // Check the file has been opened
        if (this.writer == null) {
            throw new IOException("The CSV file is not open for writing.");
        }
        
        // Check if writing these lines will go over our output file limit
        // and create the next output file in the series if necessary
        if ((this.linesWritten + lines.length) >= GlobalVariables.CSV_LINE_LIMIT) {
            this.startNextOutputFile();
            this.linesWritten = 0;
        }
        
        // Write the lines to disk (including newlines)
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            
            this.writer.write(line);
            this.writer.newLine();
        }
        this.linesWritten += lines.length;
        
        // Flush all of our changes now so nothing waits cached in memory
        this.writer.flush();
    }
    
    
    /**
     * Returns the CSV representation of the given tick snapshot as
     * an array of strings.
     * 
     * @param tick the snapshot of the tick to convert to CSV.
     * @return the array of CSV lines for the given tick snapshot.
     */
    public static String[] createTickLines(TickSnapshot tick) {
        // Check the tick snapshot exists
        if (tick == null) {
            return null;
        }
        String tickNum = "" + tick.getTickNumber();
        
        // Get the list of of vehicles stored in the tick snapshot 
        Collection<Integer> vehicleIDs = tick.getVehicleList();
        if (vehicleIDs == null || vehicleIDs.isEmpty()) {
            return null;
        }
        
        // Loop through the list of vehicles and convert each to a CSV line
        ArrayList<String> lines = new ArrayList<String>();
        for (Integer id : vehicleIDs) {
            if (id == null) {
                continue;
            }
            
            // Retrieve the vehicle snapshot from the tick snapshot
            VehicleSnapshot vehicle = tick.getVehicleSnapshot(id);
            if (vehicle == null) {
                continue;
            }
            
            // Get the CSV representation of this vehicle
            String line = CsvOutputWriter.createVehicleLine(vehicle);
            if (line == null) {
                continue;
            }
            
            // Prepend the tick number, data type token, and add to array
            line = tickNum + "," + line;
            lines.add(line);
        }
        
        // Return the array of lines from this tick snapshot
        return lines.toArray(new String[0]);
    }
    
    
    /**
     * Returns the CSV representation of the given vehicle snapshot. 
     * 
     * @param vehicle the vehicle snapshot to convert to CSV.
     * @return the CSV representation of the given vehicle snapshot.
     */
    public static String createVehicleLine(VehicleSnapshot vehicle) {
        if (vehicle == null) {
            return null;
        }
        
        // Extract the values from the vehicle snapshot
        int id = vehicle.getId();
        double prev_x = vehicle.getPrevX();
        double prev_y = vehicle.getPrevY();    
        double x = vehicle.getX();
        double y = vehicle.getY();
        float speed = vehicle.getSpeed();  
        int vehicleClass = vehicle.getvehicleClass();
        int roadID = vehicle.getRoadID();

        return (id + "," + prev_x + "," + prev_y + "," + x + "," + y + "," + speed +
        		"," + vehicleClass + "," + roadID);
    }
    
    
    /**
     * Returns a guaranteed unique absolute path for writing output
     * from the simulation within the current working directory and
     * using the filename format of "EvacSim_Output_<timestamp>.csv"
     * so long as this method is not called more frequently than once
     * per second.  If there is a problem writing to this location,
     * the file will be saved to a temporary directory determined by
     * the Java library determined by the operating system upon which
     * this program is executing.
     * 
     * @return a guaranteed unique absolute path for writing output.
     */
    public static String createDefaultFilePath() {
        // Get the default pieces of the filename to assemble
        String defaultFilename = GlobalVariables.CSV_DEFAULT_FILENAME;
        String defaultExtension = GlobalVariables.CSV_DEFAULT_EXTENSION;
        
        // Get a timestamp to use in the filename
        SimpleDateFormat formatter = 
                new SimpleDateFormat("YYYY-MM-dd-hhmm-ss");
        String timestamp = formatter.format(new Date());
        
        // Build the filename
        String filename = defaultFilename + "_" + timestamp + 
                          ".1." + defaultExtension;
        
        // Get the default directory for placing the file
        String defaultDir = GlobalVariables.CSV_DEFAULT_PATH;
        if (defaultDir == null || defaultDir.trim().length() < 1) {
                defaultDir = System.getProperty("user.dir");
        }
                
        // Build the full path to the file
        String outpath = defaultDir + File.separatorChar + filename;
        
        // Check the path will be a valid file
        File outfile = new File(outpath);
        if (outfile.exists()) {
            // A file with this name somehow already exists even though
            // we've given it a timestamp of this very second at runtime.
            // we will add the hashcode for the filename string object as
            // a bit of randomization and just hope that is good enough.
            int hashCode = System.identityHashCode(filename);
            filename = defaultFilename + "_" + timestamp + "_" +
                       hashCode + ".1." + defaultExtension;
            outpath = defaultDir + File.pathSeparator + filename;
            outfile = new File(outpath);
        }

        try {
            outfile.createNewFile();
            if (!outfile.canWrite()) {
                throw new IOException("Can't write to file.");
            }
        }
        catch (IOException ioe) {
            // We don't have permissions to write to the current directory
            // so we will have to fall back on saving this in the temp dir
            try {
                outfile = 
                    File.createTempFile(filename, defaultExtension);
            }
            catch (IOException ioe2) {
                // Our default filename failed, and now our temp file failed.
                // for this to happen, something has to be wrong with the OS
                // or the storage medium has to be full.  we give up...
                return null;
            }
        }
        
        // Return the path to whatever we decided our file would be
        return outfile.getAbsolutePath();
    }
    
}
