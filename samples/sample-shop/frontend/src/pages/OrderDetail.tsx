import React, { useEffect, useState } from 'react';
import orderApi, { changeStatus, fetchOrderItems } from '../api/orderApi';

interface OrderItem {
  itemSeq: number;
  productNm: string;
  qty: number;
  amt: number;
}

interface Order {
  orderId: number;
  orderDt: string;
  statusCode: string;
  totalAmt: number;
}

interface Props {
  orderId: number;
  onBack: () => void;
}

/**
 * 주문 상세 화면 (TSX 함수 컴포넌트).
 */
const OrderDetail: React.FC<Props> = ({ orderId, onBack }) => {
  const [order, setOrder] = useState<Order | null>(null);
  const [items, setItems] = useState<OrderItem[]>([]);

  useEffect(() => {
    // useEffect 안의 async 함수
    async function load(): Promise<void> {
      const [o, list] = await Promise.all([orderApi.fetchOrder(orderId), fetchOrderItems(orderId)]);
      setOrder(o as Order);
      setItems(list as OrderItem[]);
    }
    load();
  }, [orderId]);

  const handleShip = async (): Promise<void> => {
    await changeStatus(orderId, '30');
    setOrder((prev) => (prev ? { ...prev, statusCode: '30' } : prev));
  };

  // 컴포넌트 안에서 fetch 를 직접 쓴다 (POST, 템플릿 리터럴 URL)
  const handlePay = async (method: string): Promise<void> => {
    const res = await fetch(`/api/orders/${orderId}/payments`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ method, amount: order?.totalAmt ?? 0 }),
    });
    if (!res.ok) {
      alert('결제 실패');
    }
  };

  if (!order) {
    return <p>불러오는 중...</p>;
  }

  return (
    <section>
      <h2>주문 상세 #{order.orderId}</h2>
      <p>
        {order.orderDt} / 상태 {order.statusCode} / {order.totalAmt}원
      </p>
      <ul>
        {items.map((item) => (
          <li key={item.itemSeq}>
            {item.productNm} × {item.qty} = {item.amt}
          </li>
        ))}
      </ul>
      <button type="button" onClick={handleShip}>배송 처리</button>
      <button type="button" onClick={() => handlePay('CARD')}>카드 결제</button>
      <button type="button" onClick={onBack}>목록</button>
    </section>
  );
};

export default OrderDetail;
