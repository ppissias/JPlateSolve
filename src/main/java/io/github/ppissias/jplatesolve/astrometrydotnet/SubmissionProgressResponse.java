/*
 * SpacePixels
 * 
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package io.github.ppissias.jplatesolve.astrometrydotnet;

import java.util.Arrays;

/**
 * Submission status returned by Astrometry.net while an upload is being queued
 * or processed.
 * <p>
 * The important field for this library is {@link #getJobs()}, which becomes
 * populated once Astrometry.net has created at least one solve job for the
 * submission.
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
