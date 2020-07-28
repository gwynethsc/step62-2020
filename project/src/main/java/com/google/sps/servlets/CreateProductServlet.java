package com.google.sps.servlets;

import java.io.IOException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

//Product Search Library
import com.google.sps.data.ProductSearchLibrary;

import com.google.gson.Gson;

import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.PreparedQuery;
import com.google.appengine.api.datastore.Query;
import com.google.appengine.api.datastore.Text;
import com.google.appengine.api.datastore.Query.FilterPredicate;
import com.google.appengine.api.datastore.Query.FilterOperator;
import com.google.appengine.api.datastore.Query.Filter;

import com.google.sps.data.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

import com.google.appengine.api.users.UserService;
import com.google.appengine.api.users.UserServiceFactory;

@WebServlet("/createProduct")
public class CreateProductServlet extends HttpServlet {

  protected DatastoreService datastore;
  protected Gson gson;
  protected UserService userService;

  public CreateProductServlet() {
    super();
    datastore = DatastoreServiceFactory.getDatastoreService();
    userService = UserServiceFactory.getUserService();
    gson = new Gson();
  }

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    // Retrieve required parameters for the product set.
    String productId = request.getParameter("productId");
    boolean isNewProduct = false;
    if (productId == null) {
      isNewProduct = true;
      productId = ServletLibrary.generateUUID();
    }
    String productDisplayName = request.getParameter("productDisplayName");
    String productSetDisplayName = request.getParameter("productSetDisplayName");

    // Only add the product set if doesn't already exist in the database. 
    // Otherwise simply get the productsetId.
    // Third parameter indicates that we are searching using the display name instead of the product set id. 
    ProductSetEntity result = ServletLibrary.retrieveProductSetInfo(datastore, productSetDisplayName, true);
    String productSetId = ServletLibrary.generateUUID();
    if (result == null) {
      Entity productSet = new Entity("ProductSet", productSetId);
      productSet.setProperty("productSetId", productSetId);
      productSet.setProperty("productSetDisplayName", productSetDisplayName);
      productSet.setProperty("productIds", new ArrayList<String>());
      datastore.put(productSet);
    } else {
      productSetId = result.getProductSetId();
    }

    String productCategory = request.getParameter("productCategory");
    String businessId = userService.getCurrentUser().getUserId();

    // TODO: add businessDisplayName as a searchable tag.

    float price;
    try {
      price = Float.parseFloat(request.getParameter("price"));
    } catch (NumberFormatException e) {
      System.err.println("Float was not able to be parsed! Error:"+e);
      price = 0.0f;
    }

    // TODO: support for adding multiple images. For now, we are only adding
    // the initial image that was uploaded.
    List<String> gcsUrls = new ArrayList<>();
    gcsUrls.add(request.getParameter("mainGcsUrl"));
    
    List<String> imageUrls = new ArrayList<>();
    imageUrls.add(request.getParameter("mainImageUrl"));

    // Get annotation and labels.
    String cloudVisionAnnotation = request.getParameter("cloudVisionAnnotation");
    String productDescription = request.getParameter("productDescription");
    List<String> labels = new ArrayList<>(Arrays.asList(request.getParameterValues("labels")));
    labels.add(productDisplayName);
    labels.add(productSetDisplayName);
    String[] pieces = productCategory.split("-");
    labels.add(pieces[0]); 
    Business business = ServletLibrary.retrieveBusinessInfo(datastore, businessId);
    if (business != null) {
      labels.add(business.getBusinessDisplayName());
    }
    // Remove potential duplicates, ignoring case, but preserving it when 
    // returning the new list. We set labels to the new list that was generated.
    Set<String> seen = new HashSet<>();
    List<String> newLabels = new ArrayList<>();
    for (String label : labels) {
      if (seen.contains(label.toLowerCase())) continue;
      seen.add(label.toLowerCase());
      newLabels.add(label);
    }
    labels = newLabels;

    // Add product to tables or update relevant tables in datastore.
    if (isNewProduct) {
      ServletLibrary.addProductToLabels(datastore, productId, labels);
      ServletLibrary.addProductToProductSet(datastore, productId, productSetId);
      ServletLibrary.addProductToProductCategory(datastore, productId, productCategory);
      ServletLibrary.addProductToBusiness(datastore, productId, businessId);
      createAndAddToProductSearch(productId, productSetId, productDisplayName, productCategory, gcsUrls);
    } else {
      ProductEntity oldProduct = ServletLibrary.retrieveProductInfo(datastore, productId);
      ServletLibrary.updateProductLabels(datastore, productId, oldProduct.getLabels(), labels);
      ServletLibrary.updateProductSets(datastore, productId, oldProduct.getProductSetId(), productSetId);
      ServletLibrary.updateProductCategories(datastore, productId, oldProduct.getProductCategory(), productCategory);

      List<String> oldProductGcsUrls = oldProduct.getGcsUrls();
      for(String gcsUri : oldProductGcsUrls){
        String objectName = gcsUri.substring(gcsUri.lastIndexOf('/') + 1);
        
        gcsUri = changeGcsFormat(gcsUri);
        String newGcsUri = request.getParameter("mainGcsUrl");
        String newObjectName = newGcsUri.substring(newGcsUri.lastIndexOf('/') + 1);
        newGcsUri = changeGcsFormat(newGcsUri);
        if(!gcsUri.equals(newGcsUri)){
            ProductSearchLibrary.deleteReferenceImage(productId, objectName);
            ProductSearchLibrary.createReferenceImage(productId, newObjectName, newGcsUri);
        }
      }
    } 

    // Create a product set entity and store in datastore.
    Entity product = new Entity("Product", productId);
    product.setProperty("productId", productId);
    product.setProperty("productDisplayName", productDisplayName);
    product.setProperty("productSetId", productSetId);
    product.setProperty("productCategory", productCategory);
    product.setProperty("businessId", businessId);
    product.setProperty("price", price);
    product.setProperty("gcsUrls", gcsUrls);
    product.setProperty("imageUrls", imageUrls);
    product.setProperty("labels", labels);
    product.setProperty("productDescription", productDescription);
    product.setProperty("cloudVisionAnnotation", new Text(cloudVisionAnnotation));
    datastore.put(product);
    

    // Redirect to the appropriate page.
    if (isNewProduct) {
      response.sendRedirect("/businessAccount.html");
    } else {
      response.sendRedirect("/viewProducts.html");
    }  
    
  }

  private void createAndAddToProductSearch(String productId, String productSetId, String productDisplayName, String productCategory, List<String> gcsList) throws IOException{ 
    // Functions to create product and add to a product set in the product search database
    ProductSearchLibrary.createProduct(productId, productDisplayName, productCategory);

    ProductSearchLibrary.addProductToProductSet(productId, productSetId);

    ProductSearchLibrary.addProductToProductSet(productId, "cloudberryAllProducts");

    //Create reference image for a product to facilitate the searching for a product by image
    //image gcsuri used for reference image id
    for(String gcsUri : gcsList){
        String objectName = gcsUri.substring(gcsUri.lastIndexOf('/') + 1);
        
        gcsUri = changeGcsFormat(gcsUri);
    
        ProductSearchLibrary.createReferenceImage(productId, objectName, gcsUri);
    } 
  }

  private String changeGcsFormat(String gcsUri){
    
    String newGcsFormat = "gs://";
    
    String[] gcsArray = gcsUri.split("/");

    newGcsFormat += gcsArray[2] + "/" + gcsArray[3];
    // The last and the penultimate indexes of the split gcsUri give the strings required to reformat the 
    // gcsuri to a valid parameter for the createReferenceImage method.

    return newGcsFormat;
  }
}
