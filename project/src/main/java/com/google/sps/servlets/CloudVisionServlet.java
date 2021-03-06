package com.google.sps.servlets;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.Date;
import java.awt.Color;
import com.google.sps.data.*;

import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.Text;
import com.google.appengine.api.datastore.PreparedQuery;
import com.google.appengine.api.datastore.Query;
import com.google.appengine.api.datastore.Query.SortDirection;
import com.google.appengine.api.datastore.FetchOptions;

import java.io.IOException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.appengine.api.blobstore.BlobInfo;
import com.google.appengine.api.blobstore.BlobInfoFactory;
import com.google.appengine.api.blobstore.BlobKey;
import com.google.appengine.api.blobstore.BlobstoreService;
import com.google.appengine.api.blobstore.BlobstoreServiceFactory;
import com.google.appengine.api.blobstore.FileInfo;
import com.google.appengine.api.images.ImagesService;
import com.google.appengine.api.images.ImagesServiceFactory;
import com.google.appengine.api.images.ServingUrlOptions;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

import com.google.appengine.api.users.UserService;
import com.google.appengine.api.users.UserServiceFactory;

import com.google.cloud.vision.v1.*;
import com.google.cloud.vision.v1.WebDetection.WebEntity;
import com.google.cloud.vision.v1.WebDetection.WebLabel;
import com.google.cloud.vision.v1.WebDetection.WebPage;
import com.google.protobuf.ByteString;
import java.io.ByteArrayOutputStream;

import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;

import java.util.Random;
import java.math.BigDecimal; 


@WebServlet("/cloudVision")
public class CloudVisionServlet extends HttpServlet {

  protected Gson gson;
  protected BlobstoreService blobstore;
  protected ImagesService imagesService;
  protected List<Feature> allFeatures;
  protected DatastoreService datastore;
  protected UserService userService;

  public CloudVisionServlet() {
    super();
    gson = new Gson();
    blobstore = BlobstoreServiceFactory.getBlobstoreService();
    imagesService = ImagesServiceFactory.getImagesService();
    datastore = DatastoreServiceFactory.getDatastoreService();
    userService = UserServiceFactory.getUserService();
    
    Feature labelDetection = Feature.newBuilder().setType(Feature.Type.LABEL_DETECTION).build();
    Feature logoDetection = Feature.newBuilder().setType(Feature.Type.LOGO_DETECTION).build();
    Feature textDetection = Feature.newBuilder().setType(Feature.Type.TEXT_DETECTION).build();
    Feature imageProperties = Feature.newBuilder().setType(Feature.Type.IMAGE_PROPERTIES).build();
    Feature objectLocalization = Feature.newBuilder().setType(Feature.Type.OBJECT_LOCALIZATION).build();
    Feature webDetection = Feature.newBuilder().setType(Feature.Type.WEB_DETECTION).build();
    allFeatures = Arrays.asList(labelDetection, logoDetection, textDetection, imageProperties, objectLocalization, webDetection);
  }

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    // Retrieve the temporary vision annotation from the business account.
    Business business = ServletLibrary.retrieveBusinessInfo(datastore, userService.getCurrentUser().getUserId());
    // TODO: Handle the bug where this returns null. (It shouldn't), but I guess just redirect or something. 
    String tempVisionAnnotation = business.getTempVisionAnnotation();
    // If there is nothing stored, simply return null.
    if (tempVisionAnnotation.isEmpty()) {
      String json = null;
      response.setContentType("application/json;");
      response.getWriter().println(json);
      return;
    }

    // Extract labels and description from the cloud vision annotation, formatted
    // as a json. 
    String json = VisionLibrary.extractLabels(gson, tempVisionAnnotation);

    // Send the json of the cloud vision annotation over.
    response.setContentType("application/json;");
    response.getWriter().println(json);

    // TODO: set the tempVisionAnnotation to null once it has been sent out, so
    // that future products that are added start off with a blank form.
  }

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    // Get the URL of the image that the user uploaded.

    String gcsUrl = CloudStorageLibrary.getGcsFilePath(request, blobstore);
    BlobKey blobKey = blobstore.createGsBlobKey(gcsUrl);
    String imageUrl = "/serveBlobstoreImage?blobKey=" + blobKey.getKeyString();

    // Use blobKey to send a request to the cloud vision api. We are guaranteed 
    // that the client uploaded an image.
    byte[] blobBytes = VisionLibrary.getBlobBytes(blobstore, blobKey);
    AnnotateImageResponse imageResponse = VisionLibrary.handleCloudVisionRequest(blobBytes, allFeatures);
    String tempVisionAnnotation = VisionLibrary.formatImageResponse(gson, imageResponse, gcsUrl, imageUrl);

    // Store the response in the business account.
    ServletLibrary.updateTempAnnotation(datastore, 
                                        userService.getCurrentUser().getUserId(),
                                        new Text(tempVisionAnnotation));

    // Redirect to the create product form or the edit product form.
    boolean isEditing = Boolean.parseBoolean(request.getParameter("edit"));
    if (isEditing) {
      response.sendRedirect("/editProduct.html?refreshImage=true&productId="+request.getParameter("editProductId"));
    } else {
      response.sendRedirect("/createProduct.html");
    }
  }
}