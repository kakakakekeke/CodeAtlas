import { useCallback, useEffect, useState } from 'react';
import { fetchOrders } from '../api/orderApi';

/**
 * 주문 목록 커스텀 훅.
 */
export function useOrders(initialParams = {}) {
  const [params, setParams] = useState(initialParams);
  const [orders, setOrders] = useState([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await fetchOrders(params);
      setOrders(res.data.items);
      setTotal(res.data.total);
    } finally {
      setLoading(false);
    }
  }, [params]);

  useEffect(() => {
    load();
  }, [load]);

  return { orders, total, loading, reload: load, setParams };
}

export default useOrders;
