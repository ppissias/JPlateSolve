/**
 * 
 */
package io.github.ppissias.jplatesolve.astrometrydotnet;

import javax.annotation.processing.Generated;


/*
 * SpacePixels
 * 
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
/**
 * Parameters sent to Astrometry.net's file upload endpoint.
 * <p>
 * This type mirrors the request-json payload used by the remote API. Callers
 * typically use {@link #builder()} and only set the fields they know; omitted
 * values are left for Astrometry.net to infer or default on the server side.
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
	 * Creates a builder for an Astrometry.net upload request.
	 *
	 * @return request builder
	 */
	@Generated("SparkTools")
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for {@link SubmitFileRequest}.
	 * <p>
	 * Values correspond directly to Astrometry.net upload parameters such as the
	 * approximate image scale, approximate pointing, and source-detection tuning.
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

		/**
		 * Sets the authenticated Astrometry.net session.
		 *
		 * @param session authenticated session token
		 * @return this builder
		 */
		public Builder withSession(String session) {
			this.session = session;
			return this;
		}

		/**
		 * Controls whether the uploaded image is publicly visible on Astrometry.net.
		 *
		 * @param publicly_visible expected values are typically {@code "y"} or
		 *        {@code "n"}
		 * @return this builder
		 */
		public Builder withPublicly_visible(String publicly_visible) {
			this.publicly_visible = publicly_visible;
			return this;
		}

		/**
		 * Sets the unit used by {@code scale_lower} and {@code scale_upper}.
		 *
		 * @param scale_units Astrometry.net scale unit such as {@code degwidth},
		 *        {@code arcminwidth}, or {@code arcsecperpix}
		 * @return this builder
		 */
		public Builder withScale_units(String scale_units) {
			this.scale_units = scale_units;
			return this;
		}

		/**
		 * Sets the lower bound of the expected image scale.
		 *
		 * @param scale_lower lower bound expressed in {@link #withScale_units(String)}
		 * @return this builder
		 */
		public Builder withScale_lower(float scale_lower) {
			this.scale_lower = scale_lower;
			return this;
		}

		/**
		 * Sets the upper bound of the expected image scale.
		 *
		 * @param scale_upper upper bound expressed in {@link #withScale_units(String)}
		 * @return this builder
		 */
		public Builder withScale_upper(float scale_upper) {
			this.scale_upper = scale_upper;
			return this;
		}

		/**
		 * Sets the approximate image center right ascension in degrees.
		 *
		 * @param center_ra right ascension in the range supported by Astrometry.net
		 * @return this builder
		 */
		public Builder withCenter_ra(float center_ra) {
			this.center_ra = center_ra;
			return this;
		}

		/**
		 * Sets the approximate image center declination in degrees.
		 *
		 * @param center_dec declination in degrees
		 * @return this builder
		 */
		public Builder withCenter_dec(float center_dec) {
			this.center_dec = center_dec;
			return this;
		}

		/**
		 * Sets the search radius around the approximate center coordinates.
		 *
		 * @param radius search radius in degrees
		 * @return this builder
		 */
		public Builder withRadius(float radius) {
			this.radius = radius;
			return this;
		}

		/**
		 * Sets the image downsampling factor used before source detection.
		 *
		 * @param downsample_factor downsampling factor, commonly 2 or 4
		 * @return this builder
		 */
		public Builder withDownsample_factor(float downsample_factor) {
			this.downsample_factor = downsample_factor;
			return this;
		}

		/**
		 * Sets the expected positional error of stars in the uploaded image.
		 *
		 * @param positional_error positional error in pixels
		 * @return this builder
		 */
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
