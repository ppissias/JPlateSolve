/**
 * 
 */
package io.github.ppissias.jplatesolve;

import javax.annotation.processing.Generated;


/*
 * SpacePixels
 * 
 * Copyright (c)2020-2023, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
public class SubmitFileRequest {

	private String session; //string, requried. Your session key, required in all requests
	
	private String publicly_visible; //string: y, n
	
	private String scale_units; //string: degwidth (default), arcminwidth, arcsecperpix. The units for the scale_lower and scale_upper arguments; becomes the –scale-units argument to solve-field on the server side.
	
	private float scale_lower; //float. The lower-bound of the scale of the image.
	
	private float scale_upper; //float. The upper-bound of the scale of the image.
	 
	private float center_ra; //float, 0 to 360, in degrees. The position of the center of the image.
	
	private float center_dec; //float, -90 to 90, in degrees. The position of the center of the image.
	
	private float radius;//float, in degrees. Used with center_ra,``center_dec`` to specify that you know roughly where your image is on the sky.
	
	private float downsample_factor; //float, >1. Downsample (bin) your image by this factor before performing source detection. This often helps with saturated images, noisy images, and large images. 2 and 4 are commonly-useful values.
	
	private float positional_error; //float, expected error on the positions of stars in your image. Default is 1.

	@Generated("SparkTools")
	private SubmitFileRequest(Builder builder) {
		this.session = builder.session;
		this.publicly_visible = builder.publicly_visible;
		this.scale_units = builder.scale_units;
		this.scale_lower = builder.scale_lower;
		this.scale_upper = builder.scale_upper;
		this.center_ra = builder.center_ra;
		this.center_dec = builder.center_dec;
		this.radius = builder.radius;
		this.downsample_factor = builder.downsample_factor;
		this.positional_error = builder.positional_error;
	}


	/**
	 * Creates builder to build {@link SubmitFileRequest}.
	 * @return created builder
	 */
	@Generated("SparkTools")
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder to build {@link SubmitFileRequest}.
	 */
	@Generated("SparkTools")
	public static final class Builder {
		private String session;
		private String publicly_visible;
		private String scale_units;
		private float scale_lower;
		private float scale_upper;
		private float center_ra;
		private float center_dec;
		private float radius;
		private float downsample_factor;
		private float positional_error;

		private Builder() {
		}

		public Builder withSession(String session) {
			this.session = session;
			return this;
		}

		public Builder withPublicly_visible(String publicly_visible) {
			this.publicly_visible = publicly_visible;
			return this;
		}

		public Builder withScale_units(String scale_units) {
			this.scale_units = scale_units;
			return this;
		}

		public Builder withScale_lower(float scale_lower) {
			this.scale_lower = scale_lower;
			return this;
		}

		public Builder withScale_upper(float scale_upper) {
			this.scale_upper = scale_upper;
			return this;
		}

		public Builder withCenter_ra(float center_ra) {
			this.center_ra = center_ra;
			return this;
		}

		public Builder withCenter_dec(float center_dec) {
			this.center_dec = center_dec;
			return this;
		}

		public Builder withRadius(float radius) {
			this.radius = radius;
			return this;
		}

		public Builder withDownsample_factor(float downsample_factor) {
			this.downsample_factor = downsample_factor;
			return this;
		}

		public Builder withPositional_error(float positional_error) {
			this.positional_error = positional_error;
			return this;
		}

		public SubmitFileRequest build() {
			return new SubmitFileRequest(this);
		}
	}

	public String getPublicly_visible() {
		return publicly_visible;
	}


	public String getScale_units() {
		return scale_units;
	}


	public float getScale_lower() {
		return scale_lower;
	}


	public float getScale_upper() {
		return scale_upper;
	}


	public float getCenter_ra() {
		return center_ra;
	}


	public float getCenter_dec() {
		return center_dec;
	}


	public float getRadius() {
		return radius;
	}


	public float getDownsample_factor() {
		return downsample_factor;
	}


	public float getPositional_error() {
		return positional_error;
	}


	@Override
	public String toString() {
		return "SubmitFileRequest [session=" + session + ", publicly_visible=" + publicly_visible + ", scale_units="
				+ scale_units + ", scale_lower=" + scale_lower + ", scale_upper=" + scale_upper + ", center_ra="
				+ center_ra + ", center_dec=" + center_dec + ", radius=" + radius + ", downsample_factor="
				+ downsample_factor + ", positional_error=" + positional_error + "]";
	}


	public String getSession() {
		return session;
	}


	public void setSession(String session) {
		this.session = session;
	}


	public void setPublicly_visible(String publicly_visible) {
		this.publicly_visible = publicly_visible;
	}


	public void setScale_units(String scale_units) {
		this.scale_units = scale_units;
	}


	public void setScale_lower(float scale_lower) {
		this.scale_lower = scale_lower;
	}


	public void setScale_upper(float scale_upper) {
		this.scale_upper = scale_upper;
	}


	public void setCenter_ra(float center_ra) {
		this.center_ra = center_ra;
	}


	public void setCenter_dec(float center_dec) {
		this.center_dec = center_dec;
	}


	public void setRadius(float radius) {
		this.radius = radius;
	}


	public void setDownsample_factor(float downsample_factor) {
		this.downsample_factor = downsample_factor;
	}


	public void setPositional_error(float positional_error) {
		this.positional_error = positional_error;
	}
	
	
	
}
