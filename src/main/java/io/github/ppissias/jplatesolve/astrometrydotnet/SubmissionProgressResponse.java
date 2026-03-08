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

import java.util.Arrays;

/**
 * Documentation from 
 * http://astrometry.net/doc/net/api.html
 * 
 * Sample response
 * http://nova.astrometry.net/api/submissions/3904409
 * {"user": 1000, "processing_started": "2020-10-05 09:59:34.799331", "processing_finished": "2020-10-05 09:59:35.033692", 
 * "user_images": [4046174], "images": [9302760], "jobs": [4612037], "job_calibrations": [[4612037, 3129343]]}
 * 
 * When you submit a URL or file, you will get back a subid submission identifier. You can use this to query the status of your submission as it gets queued and run. Each submission can have 0 or more jobs associated with it; a job corresponds to a run of the solve-field program on your data.
 * 
 * If the job has not started yet, the jobs array may be empty. If the job_calibrations array is not empty, then we solved your image.
 * 
 * progressive responses
 * {"user": 1, "processing_started": "None", "processing_finished": "None", "user_images": [], "images": [], "jobs": [], "job_calibrations": []}
 * 
 * @author Petros Pissias
 *
 */
public class SubmissionProgressResponse {
	private String user;
	private String processing_started; //Date?
	private String processing_finished;
	private int[] user_images;
	private int[] images;
	private int[] jobs;
	private int[][] job_calibrations;
	public String getUser() {
		return user;
	}
	public void setUser(String user) {
		this.user = user;
	}
	public String getProcessing_started() {
		return processing_started;
	}
	public void setProcessing_started(String processing_started) {
		this.processing_started = processing_started;
	}
	public String getProcessing_finished() {
		return processing_finished;
	}
	public void setProcessing_finished(String processing_finished) {
		this.processing_finished = processing_finished;
	}
	public int[] getUser_images() {
		return user_images;
	}
	public void setUser_images(int[] user_images) {
		this.user_images = user_images;
	}
	public int[] getImages() {
		return images;
	}
	public void setImages(int[] images) {
		this.images = images;
	}
	public int[] getJobs() {
		return jobs;
	}
	public void setJobs(int[] jobs) {
		this.jobs = jobs;
	}
	public int[][] getJob_calibrations() {
		return job_calibrations;
	}
	public void setJob_calibrations(int[][] job_calibrations) {
		this.job_calibrations = job_calibrations;
	}
	@Override
	public String toString() {
		return "SubmissionProgressResponse [user=" + user + ", processing_started=" + processing_started
				+ ", processing_finished=" + processing_finished + ", user_images=" + Arrays.toString(user_images)
				+ ", images=" + Arrays.toString(images) + ", jobs=" + Arrays.toString(jobs) + ", job_calibrations="
				+ Arrays.toString(job_calibrations) + "]";
	}
	
	
}
