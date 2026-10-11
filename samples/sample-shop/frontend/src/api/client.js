import axios from 'axios';

/**
 * 공용 axios 인스턴스.
 * baseURL 이 '/api' 이므로 client.get('/orders') 의 실제 요청 경로는 '/api/orders' 다.
 */
const client = axios.create({
  baseURL: '/api',
  timeout: 5000,
  headers: { 'Content-Type': 'application/json' },
});

client.interceptors.response.use(
  (response) => response,
  (error) => {
    console.error('API 오류', error);
    return Promise.reject(error);
  },
);

export default client;
