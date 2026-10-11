import React, { useState } from 'react';
import { deleteOrder, exportOrders } from '../api/orderApi';
import { useOrders } from '../hooks/useOrders';

/**
 * 주문 목록 화면 (함수 컴포넌트 + 훅).
 */
export default function OrderList({ onSelect }) {
  const [keyword, setKeyword] = useState('');
  const { orders, total, loading, reload, setParams } = useOrders({ page: 1, size: 20 });

  const handleSearch = (e) => {
    e.preventDefault();
    setParams({ page: 1, size: 20, memberNm: keyword });
  };

  // async 화살표 함수 핸들러
  const handleDelete = async (orderId) => {
    if (!window.confirm('주문을 취소하시겠습니까?')) {
      return;
    }
    await deleteOrder(orderId);
    reload();
  };

  function handleExport() {
    exportOrders('Orders').then((res) => console.log('내보내기', res.data.length));
  }

  if (loading) {
    return <p>불러오는 중...</p>;
  }

  return (
    <section>
      <h2>주문 목록 ({total}건)</h2>
      <form onSubmit={handleSearch}>
        <input value={keyword} onChange={(e) => setKeyword(e.target.value)} placeholder="회원명" />
        <button type="submit">검색</button>
        <button type="button" onClick={handleExport}>내보내기</button>
      </form>
      <table>
        <tbody>
          {orders.map((order) => (
            <tr key={order.orderId}>
              <td>
                <a href="#" onClick={() => onSelect(order.orderId)}>{order.orderId}</a>
              </td>
              <td>{order.orderDt}</td>
              <td>{order.totalAmt}</td>
              <td>
                <button type="button" onClick={() => handleDelete(order.orderId)}>취소</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
