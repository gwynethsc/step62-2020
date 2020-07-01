package com.google.sps.servlets;
 
import com.google.api.gax.longrunning.OperationFuture;
import com.google.cloud.vision.v1.*;
 
import java.io.IOException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.*;
import com.google.sps.data.ServletLibrary;
 
@WebServlet("/create-product")
public class CreateProductServlet extends HttpServlet {
 
  public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
    String projectId = "cloudberry-step-2";
    String computeRegion = "us-east1";
    String productId = request.getParameter("product-id");
    String productDisplayName = request.getParameter("product-display");
    String productCategory = "toys-v2";
    String setId;
   
    ServletsLibrary.createProduct(projectId, computeRegion, productId, productDisplayName, productCategory);

    ServletsLibrary.addProductToProductSet(projectId, computeRegion, productId, setId);
    
    response.sendRedirect("create-product.html");

  } 
}