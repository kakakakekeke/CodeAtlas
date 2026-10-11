import axios from 'axios';
import client from './client';

/** 상품 API 모듈 (TypeScript) */

export interface Product {
  productId: number;
  productNm: string;
  price: number;
  categoryNm?: string;
  stockQty: number;
}

export interface Category {
  categoryId: number;
  parentId: number | null;
  categoryNm: string;
  children: Category[];
}

export type ProductQuery = Partial<Pick<Product, 'productNm'>> & { categoryId?: number };

const API_BASE = '/api';

export async function fetchProducts(query: ProductQuery = {}): Promise<Product[]> {
  const res = await client.get<Product[]>('/products', { params: query });
  return res.data;
}

export async function fetchProduct(productId: number): Promise<Product> {
  const res = await client.get<Product>(`/products/${productId}`);
  return res.data;
}

// fetch + 리터럴 URL
export async function fetchCategoryTree(): Promise<Category[]> {
  const res = await fetch('/api/products/categories/tree');
  if (!res.ok) {
    throw new Error(`카테고리 조회 실패: ${res.status}`);
  }
  return (await res.json()) as Category[];
}

// axios 를 인스턴스 없이 직접 쓴다. 상수(API_BASE)가 들어간 템플릿 리터럴 URL.
export const updateStock = (productId: number, qty: number): Promise<void> =>
  axios.put(`${API_BASE}/products/${productId}/stock`, { qty }).then(() => undefined);

export async function fetchLowStock(limit = 10): Promise<Product[]> {
  const res = await client.get<Product[]>('/products/low-stock', { params: { limit } });
  return res.data;
}
