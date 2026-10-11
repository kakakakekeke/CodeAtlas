import client from './client';

/**
 * 주문 API 모듈. 컴포넌트는 여기 함수를 import 해서 쓴다.
 * client 의 baseURL('/api')이 앞에 붙는다.
 */

// 화살표 함수 + named export
export const fetchOrders = (params) => client.get('/orders', { params });

// async 함수 + 템플릿 리터럴 URL
export async function fetchOrder(id) {
  const res = await client.get(`/orders/${id}`);
  return res.data;
}

export async function createOrder(order) {
  const res = await client.post('/orders', order);
  return res.data.orderId;
}

export const changeStatus = (id, status) =>
  client.put(`/orders/${id}/status`, { status, userId: 'react' });

export function deleteOrder(id) {
  return client.delete(`/orders/${id}`);
}

// fetch 를 직접 쓴다 (baseURL 이 붙지 않으므로 '/api' 를 직접 적었다)
export async function fetchOrderItems(id) {
  const res = await fetch(`/api/orders/${id}/items`);
  return res.json();
}

// 경로를 조각 배열로 조립한다
function buildUrl(...parts) {
  return '/' + parts.filter(Boolean).join('/');
}

/**
 * 내보내기. URL 을 함수 호출 결과로 만들기 때문에 정적으로 해석할 수 없다.
 * (실제 값은 '/orders/export' → '/api/orders/export')
 */
export function exportOrders(type) {
  const url = buildUrl('orders', 'export');
  return client.get(url, { params: { type } });
}

const orderApi = { fetchOrders, fetchOrder, createOrder, changeStatus, deleteOrder, fetchOrderItems, exportOrders };

export default orderApi;
