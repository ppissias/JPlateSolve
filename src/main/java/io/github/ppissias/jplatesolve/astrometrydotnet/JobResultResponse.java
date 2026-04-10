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
 * Solve status returned by Astrometry.net for a specific job.
 * <p>
 * A successful response may include a {@link JobResultResponseCalibration}
 * block with the solved sky coordinates, image scale, and orientation.
 */
public class JobResultResponse {
	/**
	 * Astrometric calibration details returned for a successfully solved job.
	 */
	public class JobResultResponseCalibration {
		private float ra;
		private float dec;
		private float radius;
		private float pixscale;
		private float orientation;
		private float parity;
		public float getRa() {
			return ra;
		}
		public void setRa(float ra) {
			this.ra = ra;
		}
		public float getDec() {
			return dec;
		}
		public void setDec(float dec) {
			this.dec = dec;
		}
		public float getRadius() {
			return radius;
		}
		public void setRadius(float radius) {
			this.radius = radius;
		}
		public float getPixscale() {
			return pixscale;
		}
		public void setPixscale(float pixscale) {
			this.pixscale = pixscale;
		}
		public float getOrientation() {
			return orientation;
		}
		public void setOrientation(float orientation) {
			this.orientation = orientation;
		}
		public float getParity() {
			return parity;
		}
		public void setParity(float parity) {
			this.parity = parity;
		}
		@Override
		public String toString() {
			return "JobResultResponseCalibration [ra=" + ra + ", dec=" + dec + ", radius=" + radius + ", pixscale="
					+ pixscale + ", orientation=" + orientation + ", parity=" + parity + "]";
		}
		
	}
	private String[] objects_in_field;
	private String[] machine_tags;
	private String[] tags;
	private String status;
	private String original_filename;
	private JobResultResponseCalibration calibration;
	public String[] getObjects_in_field() {
		return objects_in_field;
	}
	public void setObjects_in_field(String[] objects_in_field) {
		this.objects_in_field = objects_in_field;
	}
	public String[] getMachine_tags() {
		return machine_tags;
	}
	public void setMachine_tags(String[] machine_tags) {
		this.machine_tags = machine_tags;
	}
	public String[] getTags() {
		return tags;
	}
	public void setTags(String[] tags) {
		this.tags = tags;
	}
	public String getStatus() {
		return status;
	}
	public void setStatus(String status) {
		this.status = status;
	}
	public String getOriginal_filename() {
		return original_filename;
	}
	public void setOriginal_filename(String original_filename) {
		this.original_filename = original_filename;
	}
	public JobResultResponseCalibration getCalibration() {
		return calibration;
	}
	public void setCalibration(JobResultResponseCalibration calibration) {
		this.calibration = calibration;
	}
	@Override
	public String toString() {
		return "JobResultResponse [objects_in_field=" + Arrays.toString(objects_in_field) + ", machine_tags="
				+ Arrays.toString(machine_tags) + ", tags=" + Arrays.toString(tags) + ", status=" + status
				+ ", original_filename=" + original_filename + ", calibration=" + calibration + "]";
	}
	
}
