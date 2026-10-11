package com.example.shop.product.web;

import static com.example.shop.common.CodeConstants.API_PREFIX;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.shop.product.service.ProductService;
import com.example.shop.product.vo.CategoryVO;
import com.example.shop.product.vo.ProductVO;

/**
 * 상품 REST API.
 * 클래스 수준 경로가 상수 연결(API_PREFIX + "/products")이다. 실제 경로는 /api/products.
 */
@RestController
@RequestMapping(API_PREFIX + "/products")
public class ProductApiController {

    private final ProductService productService;

    public ProductApiController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public List<ProductVO> list(@RequestParam Map<String, Object> param) {
        return productService.getProducts(param);
    }

    @GetMapping("/categories/tree")
    public List<CategoryVO> categoryTree() {
        return productService.getCategoryTree();
    }

    @GetMapping("/low-stock")
    public List<ProductVO> lowStock(@RequestParam(defaultValue = "10") int limit) {
        return productService.getLowStockProducts(limit);
    }

    @GetMapping("/{productId}")
    public ProductVO get(@PathVariable Long productId) {
        return productService.getProduct(productId);
    }

    @PutMapping("/{productId}/stock")
    public void updateStock(@PathVariable Long productId, @RequestBody Map<String, Integer> body) {
        productService.increaseStock(productId, body.get("qty"));
    }

    @PostMapping
    public ProductVO create(@RequestBody ProductVO product) {
        return productService.register(product);
    }
}
