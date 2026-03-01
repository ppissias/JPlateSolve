# Astrometry.net java library
This is a java library for plate solving astronomical images using the http://nova.astrometry.net/ web api. You can also checkout [my blog](https://startales.eu/code/2020/10/08/a-java-library-for-using-the-astrometry-net-online-services/) on this. 

## Usage
```java
//blind solve
File file = new File("...");
Future<PlateSolveResult> solveResult = astrometryLib.blindSolve(file);
PlateSolveResult result = solveResult.get(); //may take some minutes
if (result.isSuccess()) {
	System.out.println("Hurrayyy.."+result.getSolveInformation());
} else {
	System.out.println("Unfortunately astrometry.net could not solve your image");
}		

//using custom parameters for solving the image
SubmitFileRequest customSolveParameters = SubmitFileRequest.builder().withPublicly_visible("y").withScale_units("degwidth")
		.withScale_lower(0.1f).withScale_upper(180.0f).withDownsample_factor(2f).withRadius(1.0f).build();
Future<PlateSolveResult> solveResult = astrometryLib.customSolve(astronomicalFile, customSolveParameters);
//if the file is a FITS file the OBJCTRA and OBJCTDEC information from the header will be used if not provided in the customSolveParameters object
//...
```
Below you can see how the data is structured and returned through the ```PlateSolveResult.getSolveInformation()``` call
```java
Map<String, String> solveInformation = new HashMap<String, String>();
solveInformation.put("source", "astrometry.net");
solveInformation.put("original_response", gson.toJson(jobResResponse));
solveInformation.put("annotated_image_link", annotategImageLink+jobID);
solveInformation.put("status_page_link", resultsPageLink+submitFileResponse.getSubid());
//all properties

solveInformation.put("dec",""+jobResResponse.getCalibration().getDec());
solveInformation.put("ra",""+jobResResponse.getCalibration().getRa());
solveInformation.put("orientation",""+jobResResponse.getCalibration().getOrientation());
solveInformation.put("pixscale",""+jobResResponse.getCalibration().getPixscale());
solveInformation.put("radius",""+jobResResponse.getCalibration().getRadius());
solveInformation.put("parity",""+jobResResponse.getCalibration().getParity());
```
## Download

[Download latest release](https://github.com/ppissias/AstrometryNetLib/releases/download/v0.2/AstrometryNetLib.jar)

## Compiling
You need to have Java 11 installed
```
gradlew build -x test
```
and the resulting .jar library can be found in build/libraries
