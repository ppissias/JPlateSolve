/*
 * SpacePixels
 * 
 * Copyright (c)2020-2023, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jplatesolve.astrometrydotnet;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.logging.Logger;

import com.google.gson.Gson;

import io.github.ppissias.jplatesolve.PlateSolveResult;
import io.github.ppissias.jplatesolve.astrometrydotnet.util.SubmitFileBodyPublisher;
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;
import nom.tam.fits.Header;
import nom.tam.fits.HeaderCard;
import nom.tam.util.Cursor;

/**
 * main class interfacing the astrometry.net services
 * 
 * @author Petros Pissias
 *
 */
public class AstrometryDotNet {

	//astrometry.net Service URIs
	private static final String loginURI = "https://nova.astrometry.net/api/login";
	private static final String submitFileURI = "https://nova.astrometry.net/api/upload";
	private static final String submissionProgressURI = "https://nova.astrometry.net/api/submissions/";//+SUBID
	private static final String jobProgressURI = "https://nova.astrometry.net/api/jobs/"; //+JOBID/info
	private static final String wcsBaseURI = "https://nova.astrometry.net/wcs_file/"; //+JOBID
	//links with info
	private static final String annotategImageLink = "https://nova.astrometry.net/annotated_display/"; //+JOBID
	private static final String resultsPageLink = "https://nova.astrometry.net/status/"; //+StatusID
	
	//Astrometry.net session ID, after logging
	private String sessionID = null; 	
	
	//Gson object used for JSON transformations to java objects back and forth
	private final Gson gson;
	
	//logger
	private final Logger logger; 

	//HTTP client used to make calls to Astrometry.net
	private final HttpClient client = HttpClient.newBuilder().version(Version.HTTP_2).build();
	
	//Executor service
	private final ExecutorService executor = Executors.newFixedThreadPool(3);
	
	/**
	 * Constructs new Object.
	 * following the login operation a user of this class may submit an image
	 * for either blind of near solving and wait until the results are available. 
	 */
	public AstrometryDotNet() {
		gson = new Gson();
		logger = Logger.getLogger(AstrometryDotNet.class.getName());
	}

	/**
	 * Returns the session ID of this intance.
	 * @return
	 */
	public String getSessionID() {
		return this.sessionID;
	}
	
	/**
	 * Logs in to astrometry.net and obtains a session ID
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public void login() throws IOException, InterruptedException {
		LoginRequest loginReq = LoginRequest.builder().withApikey("XXXXXXXX").build();
		
		logger.fine("Sending Login request [JSON]:"+gson.toJson(loginReq));		
		
		var builder = new StringBuilder();
		builder.append(URLEncoder.encode("request-json", StandardCharsets.UTF_8));
		builder.append("=");
		builder.append(URLEncoder.encode(gson.toJson(loginReq), StandardCharsets.UTF_8));
		
		logger.fine("Formatted HTTP POST request:"+builder.toString());
		
		//http POST request
		HttpRequest request = HttpRequest.newBuilder()
				.POST(HttpRequest.BodyPublishers.ofString(builder.toString()))
				.headers("Content-Type", "application/x-www-form-urlencoded")
				.uri(URI.create(loginURI))				
				.build();
		
		//make the http request and read the response
		HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
		logger.fine("HTTP POST request to "+loginURI+" succesfully made");
		logger.fine("Response header:"+response.headers());
		logger.fine("Response body:"+response.body());
		logger.fine("Response status code:"+response.statusCode());
				
		LoginResponse loginResponse = gson.fromJson(response.body(), LoginResponse.class);
		logger.fine("Transformed Login Response:"+loginResponse);
		
		this.sessionID = loginResponse.getSession();
		
	}


	/**
	 * Makes a blind solve request to Astrometry.net
	 * It will login if the user has not yet logged in. The result may take up to 5-10 minutes 
	 * depending on how busy astrometry.net currently is. It will upload the provided file 
	 * and return a Future that will eventually have the {@link PlateSolveResult}
	 * @param imageFile the image file
	 * @return a Future that can be used to obtain the solve result
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public Future<PlateSolveResult> blindSolve(File imageFile) throws IOException, InterruptedException {
		logger.fine("Will try to blind solve:"+imageFile.getAbsolutePath());
		//login if not yet logged in
		if (sessionID == null) {
			login();
		}
		
		//make the request
		SubmitFileRequest fileBlindSolveRequest = SubmitFileRequest.builder().withSession(sessionID).withPublicly_visible("y").build();
		
		//get the task that will be executed and returned to the user
		FutureTask<PlateSolveResult> task = createSolveTask(imageFile,fileBlindSolveRequest);
		
		executor.execute(task);
		return task;					
	}
	
	
	/**
	 * Makes a "custom" solve of the image. A custom solve allows the user
	 * to specify any number of parameters he wants that will influence the processing of the image.
	 * All user parameters are provided in the {@link SubmitFileRequest} parameter. 
	 * These are the parameters from astrometry.net.
	 * If the image is a FITS file then the following properties from the header will be read and provided.
	 * Priority for the parameters is on the {@link SubmitFileRequest} parameter in case a paeameter
	 * is also specified in the FITS header.
	 * @param imageFile the file to be solved.
	 * @return a Future that can be used to obtain the solve result
	 * @throws IOException 
	 * @throws InterruptedException
	 * @throws FitsException 
	 */
	public Future<PlateSolveResult> customSolve(File imageFile, SubmitFileRequest inputParameters) throws IOException, InterruptedException, FitsException {
		logger.fine("Will try to custom solve:"+imageFile.getAbsolutePath()+" with initial parameters"+inputParameters.toString());
		//login if not yet logged in
		if (inputParameters.getSession() == null) {
			login();
			inputParameters.setSession(this.sessionID);
		}
				
		boolean isFITS = false;
		String[] acceptedFileTypes = {"fits","fit","fts","Fits","Fit","FIT","FTS","Fts","FITS"};
		for (String acceptedFileEnd :acceptedFileTypes) {
			if (imageFile.getName().endsWith(acceptedFileEnd)) {
				isFITS = true;
			} 
		}
		
		SubmitFileRequest updatedParameters;
		if (isFITS) {
			//update the parameters file from properties from the FITS header
			updatedParameters = updateFromFitsHeader(imageFile, inputParameters);
		} else {
			updatedParameters = inputParameters;
		}
		
		/**
		 * Just some info:
		 * Same image
		 * 
		 * Successful solve log (parameters)
		 * 2020-10-05 13:13:41,757 running: augment-xylist --out /home/nova/nova/net/data/jobs/0461/04612276/job.axy --scale-low 0.1 --scale-high 180.0 --scale-units degwidth --wcs wcs.fits --corr corr.fits --rdls rdls.fits --downsample 2 --image /home/nova/nova/net/data/files/uploaded/eb7/eb798c47b13aaf75d2aadeba61a1174ce781db61 --tweak-order 2
		 * 2020-10-06 09:32:03,676 running: augment-xylist --out /home/nova/nova/net/data/jobs/0461/04614244/job.axy --scale-low 0.1 --scale-high 180.0 --scale-units degwidth --wcs wcs.fits --corr corr.fits --rdls rdls.fits --ra 15.308889 --dec 2.067222 --downsample 2 --image /home/nova/nova/net/data/files/uploaded/0da/0da749451f426f994dd55f520c06be8cf4b5c7b6 --tweak-order 2
		 * Failed solve log (parameters)
		 * 2020-10-06 08:59:55,845 running: augment-xylist --out /home/nova/nova/net/data/jobs/0461/04614175/job.axy --scale-units degwidth --wcs wcs.fits --corr corr.fits --rdls rdls.fits --ra 15.308889 --dec 2.067222 --image /home/nova/nova/net/data/files/uploaded/0da/0da749451f426f994dd55f520c06be8cf4b5c7b6 --tweak-order 2
		 */
		
		if (updatedParameters.getScale_units() == null) {
			//specify something
			logger.info("did not find any scale units, perhaps specify scale-low=0.1 scale-high=180.0 scale-units=degwidth");
			//updatedParameters.setScale_units("degwidth");
			//updatedParameters.setScale_lower(0.1f);
			//updatedParameters.setScale_upper(180.0f);					
		}
			
		if (updatedParameters.getDownsample_factor() == 0.0f) {
			logger.info("did not find any downsample factor, perhaps specify downsample=2");
			//updatedParameters.setDownsample_factor(2f);
		}		
		
		if (updatedParameters.getRadius() == 0.0f) {
			logger.info("did not find any radius, perhaps specify radius=10");
			//updatedParameters.setRadius(10.0f);
		}		
		
		//get the task that will be executed and returned to the user
		FutureTask<PlateSolveResult> task = createSolveTask(imageFile,updatedParameters);
		
		executor.execute(task);
		return task;					
	}	
	
	/**
	 * Used internally to create the Future task that will make the requests to astrometry.net
	 * @param imageFile
	 * @param inputParameters
	 * @return
	 */
	private FutureTask<PlateSolveResult> createSolveTask(File imageFile, SubmitFileRequest inputParameters) {

		//prepare the result Future and return it to the caller 
		FutureTask<PlateSolveResult> task = new FutureTask<PlateSolveResult>(new Callable<PlateSolveResult>() {
			
			@Override
			public PlateSolveResult call() throws Exception  {
				//make the multipart request
				
				//multipart request content separator random part
				String boundary = new BigInteger(256, new Random()).toString();

				//json parameter for the request
				String fileBlindSolveRequestJSON = gson.toJson(inputParameters);
				
				logger.fine("JSON parameter for blind solve request:"+fileBlindSolveRequestJSON);

				// Generate the byte array (this gives Java the exact size)
				byte[] multipartBody = SubmitFileBodyPublisher.buildMultipartData(boundary, fileBlindSolveRequestJSON, imageFile);

				//http POST request file upload and solve
				HttpRequest request = HttpRequest.newBuilder()
						//.POST(SubmitFileBodyPublisher.getBodyPublisher(imageFile, fileBlindSolveRequestJSON, boundary))
						.POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
						.headers("Content-Type", "multipart/form-data;boundary=" + boundary)
						.uri(URI.create(submitFileURI))				
						.build();


				//make the http request and read the response
				HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
				logger.fine("HTTP POST request to "+submitFileURI+" succesfully made");
				logger.fine("Response header:"+response.headers());
				logger.fine("Response body:"+response.body());
				logger.fine("Response status code:"+response.statusCode());
						
				SubmitFileResponse submitFileResponse = gson.fromJson(response.body(), SubmitFileResponse.class);
				logger.fine("Transformed file submit Response:"+submitFileResponse);
				
				//check initial response
				if (!submitFileResponse.getStatus().equals("success") ) {
					logger.warning("Submit request was not succesful");
					//problem with submission
					
					PlateSolveResult ret = new PlateSolveResult(false, submitFileResponse.toString(), "", null);				
					return ret;					
				}
				
				//wait for job ID to become available
				int jobID = -1;				
				while (jobID == -1) {
					logger.fine("Will check if the request is being processed");
					//check status on the request
					request = HttpRequest.newBuilder().GET().uri(URI.create(submissionProgressURI+submitFileResponse.getSubid())).build();
					
					//make the http request and read the response
					response = client.send(request, BodyHandlers.ofString());
					logger.fine("HTTP GET request to "+submissionProgressURI+submitFileResponse.getSubid()+" succesfully made");
					logger.fine("Response header:"+response.headers());
					logger.fine("Response body:"+response.body());
					logger.fine("Response status code:"+response.statusCode());
					
					/**
					 * workaround for "bug" on response that return "jobs": [null], which cannot be parsed by Gson
					 */
					if (response.body().contains("[null]")) {
						// do nothing, not ready yet
						Thread.sleep(5000);
						continue;
					} 
					//response looks OK, decode
					SubmissionProgressResponse subProgResponse = gson.fromJson(response.body(), SubmissionProgressResponse.class);
					logger.fine("Transformed SubmissionProgressResponse :"+subProgResponse);
					
					//see if there is a JOB id assigned
					int[] jobs = subProgResponse.getJobs();
					if (jobs.length >0) {
						if (jobs.length > 1) {
							//not sure what to do here... 
						}
						
						jobID = jobs[0];
						logger.fine("JOB id became available:"+jobID);
					} else {
						Thread.sleep(5000);
						logger.fine("JOB id not yet available");						
					}						
				}

				
				//wait for JOB result
				String jobStatus = null;
				JobResultResponse jobResResponse = null;
				
				while (jobStatus == null) {
					logger.fine("Will check if job has been completed");
					//check JOB status
					//check status on the request
					request = HttpRequest.newBuilder().GET().uri(URI.create(jobProgressURI+jobID+"/info")).build();
					
					//make the http request and read the response
					response = client.send(request, BodyHandlers.ofString());
					logger.fine("HTTP GET request to "+jobProgressURI+jobID+"/info succesfully made");
					logger.fine("Response header:"+response.headers());
					logger.fine("Response body:"+response.body());
					logger.fine("Response status code:"+response.statusCode());
						
					/**
					 * Quick sanity check on response to make sure it is parseable by Gson
					 */
					if (response.body().contains("[null]")) {
						// do nothing, not ready yet
						Thread.sleep(5000);
						continue;
					} 
					
					jobResResponse = gson.fromJson(response.body(), JobResultResponse.class);
					logger.fine("Transformed JobResultResponse :"+jobResResponse);
					
					String returnedJobStatus = jobResResponse.getStatus();
					if (returnedJobStatus == null) {
						Thread.sleep(5000);
						logger.fine("JOB not yet completed");
					} else if (returnedJobStatus.equals("") || returnedJobStatus.equals("solving")) {
						Thread.sleep(5000);
						logger.fine("JOB not yet completed");						
					} else if (returnedJobStatus.equals("success") || returnedJobStatus.equals("failure")){
						//we have the status
						jobStatus = returnedJobStatus;
						logger.fine("JOB completed");
					}
				}
				
				//job finished. return result 
				if (jobResResponse.getStatus().equals("success")) {
					logger.fine("Image solving was sucecesful :"+jobResResponse.toString());
					//return the solve result to the user
					
					Map<String, String> solveInformation = new HashMap<String, String>();
					solveInformation.put("source", "astrometry.net");
					solveInformation.put("original_response", gson.toJson(jobResResponse));
					solveInformation.put("annotated_image_link", annotategImageLink+jobID);
					solveInformation.put("status_page_link", resultsPageLink+submitFileResponse.getSubid());
					solveInformation.put("wcs_link", wcsBaseURI+jobID);
					//all properties
					
					solveInformation.put("dec",""+jobResResponse.getCalibration().getDec());
					solveInformation.put("ra",""+jobResResponse.getCalibration().getRa());
					solveInformation.put("orientation",""+jobResResponse.getCalibration().getOrientation());
					solveInformation.put("pixscale",""+jobResResponse.getCalibration().getPixscale());
					solveInformation.put("radius",""+jobResResponse.getCalibration().getRadius());
					solveInformation.put("parity",""+jobResResponse.getCalibration().getParity());
					
					//return
					PlateSolveResult ret = new PlateSolveResult(true, "", "", solveInformation);	
					logger.fine("Will return to the user:"+ret.toString());
					return ret;
				} else {
					//return the solve result ot the user	
					logger.fine("Image solving was not sucecesful :"+jobResResponse.toString());
					PlateSolveResult ret = new PlateSolveResult(false, jobResResponse.toString(), "", null);
					logger.fine("Will return to the user:"+ret.toString());
					return ret;
				}
			}			
		});		
		
		return task;
	}
	
	/**
	 * Will update the provided SubmitFileRequest parameters with values from the FITS header
	 * It will only update the parameters if a corresponding value is not present in the SubmitFileRequest object.
	 * @param fitsFile the FITS file
	 * @param parameters the solve parameters
	 * @return an updated SubmitFileRequest object 
	 * @throws FitsException 
	 * @throws IOException 
	 */
	private SubmitFileRequest updateFromFitsHeader(File imageFile, SubmitFileRequest parameters) throws FitsException, IOException {
		//TODO implement
		
		//open as fits file
		Fits fitsImageFile = new Fits(imageFile);
		Header fitsHeader = fitsImageFile.getHDU(0).getHeader();
		Cursor<String, HeaderCard> iter = fitsHeader.iterator();
		while (iter.hasNext()) {
			HeaderCard fitsHeaderCard = iter.next();
			String headerKeyword = fitsHeaderCard.getKey();
			String headerKeywordValue = fitsHeaderCard.getValue();
			
			logger.fine("Processing fits header keyword:"+headerKeyword+" which has value:"+headerKeywordValue);
			switch (headerKeyword)  {
			case "OBJCTRA" : {
				logger.fine("found keyword OBJCTRA");
				if (parameters.getCenter_ra() == 0.0f) {
					try {
						//update since it had no value (default value)
						parameters.setCenter_ra(getRA(headerKeywordValue));
						logger.fine("updated with value:"+parameters.getCenter_ra());
					} catch (IllegalArgumentException ex) {
						logger.warning(ex.getMessage()+" will not update RA value");
					}
				}else {
					logger.fine("will not update since the provided object has value :"+parameters.getCenter_ra());
				}
				break;
			}
			case "OBJCTDEC" : {
				logger.fine("found keyword OBJCTDEC");
				if (parameters.getCenter_dec() == 0.0f) {
					try {						
						//update since it had no value (default value)
						parameters.setCenter_dec(getDEC(headerKeywordValue));
						logger.fine("updated with value:"+parameters.getCenter_dec());
					}catch (IllegalArgumentException ex) {
						logger.warning(ex.getMessage()+" will not update DEC value");
					}
				}else {
					logger.fine("will not update since the provided object has value :"+parameters.getCenter_dec());
				}
				break;
			}
			default: {
				
			}
			}
		}	
		
		fitsImageFile.close();
		return parameters;
	}

	
	/**
	 * Will return the RA in float format from a String representation
	 * @param RAString
	 * @return
	 */
	private float getRA (String RAString) {
		/**
		 * 46	OBJCTRA	'15 18 32'	 THE RA OF THE IMAGE CENTER                     	S
		 * 47	OBJCTDEC	'+02 04 02'	 THE DEC OF THE IMAGE CENTER                    	S	
		 */
		//strip
		String RAStringProc = RAString.replaceAll("'", "");
		RAStringProc = RAStringProc.replaceAll("\"", "");
		
		String[] coordinates = RAStringProc.split(" ");
		if (coordinates.length != 3) {
			throw new IllegalArgumentException("Cannot decode "+RAString+" as RA");
		}
		
		float result = Float.parseFloat(coordinates[0])*15;
		result += (Float.parseFloat(coordinates[1]) * 0.25);
		result += (Float.parseFloat(coordinates[2]) * 0.00417);
		
		return result;
	}
	
	/**
	 * Will return the DEC in float format from a String representation
	 * @param RAString
	 * @return
	 */
	private float getDEC (String DECString) {
		/**
		 * 46	OBJCTRA	'15 18 32'	 THE RA OF THE IMAGE CENTER                     	S
		 * 47	OBJCTDEC	'+02 04 02'	 THE DEC OF THE IMAGE CENTER                    	S	
		 */
		//strip
		String DECStringProc = DECString.replaceAll("'", "");
		DECStringProc = DECStringProc.replaceAll("\"", "");
		
		String[] coordinates = DECStringProc.split(" ");
		if (coordinates.length != 3) {
			throw new IllegalArgumentException("Cannot decode "+DECString+"as DEC");
		}
		
		float result = Float.parseFloat(coordinates[0]);
		if (result < 0) {
			result -= Float.parseFloat(coordinates[1]) / 60;
		} else {
			result += Float.parseFloat(coordinates[1]) / 60;
		}
		if (result < 0) {
			result -= Float.parseFloat(coordinates[2]) / 3600;
		} else {
			result += Float.parseFloat(coordinates[2]) / 3600;
		}
		
		return result;
	}	
}
