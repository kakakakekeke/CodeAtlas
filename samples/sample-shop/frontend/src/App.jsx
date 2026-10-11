import React, { useState } from 'react';
import { createRoot } from 'react-dom/client';
import CategoryTree from './components/CategoryTree';
import OrderDetail from './pages/OrderDetail';
import OrderList from './pages/OrderList';
import ProductStock from './pages/ProductStock';

/**
 * 최상위 컴포넌트. 탭으로 화면을 바꾼다.
 */
function App() {
  const [tab, setTab] = useState('orders');
  const [selectedOrderId, setSelectedOrderId] = useState(null);

  const renderOrders = () =>
    selectedOrderId === null ? (
      <OrderList onSelect={setSelectedOrderId} />
    ) : (
      <OrderDetail orderId={selectedOrderId} onBack={() => setSelectedOrderId(null)} />
    );

  return (
    <div>
      <nav>
        <button type="button" onClick={() => setTab('orders')}>주문</button>
        <button type="button" onClick={() => setTab('stock')}>재고</button>
        <button type="button" onClick={() => setTab('category')}>카테고리</button>
      </nav>
      {tab === 'orders' && renderOrders()}
      {tab === 'stock' && <ProductStock />}
      {tab === 'category' && <CategoryTree />}
    </div>
  );
}

createRoot(document.getElementById('root')).render(<App />);

export default App;
