import React, { Component } from 'react';
import { fetchLowStock, fetchProducts, updateStock } from '../api/productApi';

/**
 * 재고 관리 화면 (클래스 컴포넌트).
 */
class ProductStock extends Component {
  constructor(props) {
    super(props);
    this.state = { products: [], lowStock: [], qty: {} };
    this.handleSave = this.handleSave.bind(this);
  }

  componentDidMount() {
    this.load();
  }

  async load() {
    const [products, lowStock] = await Promise.all([fetchProducts(), fetchLowStock(5)]);
    this.setState({ products, lowStock });
  }

  handleChange = (productId, value) => {
    this.setState((prev) => ({ qty: { ...prev.qty, [productId]: Number(value) } }));
  };

  async handleSave(productId) {
    await updateStock(productId, this.state.qty[productId] || 0);
    await this.load();
  }

  render() {
    const { products, lowStock } = this.state;
    return (
      <section>
        <h2>재고 관리</h2>
        <p>안전 재고 미달: {lowStock.map((p) => p.productNm).join(', ')}</p>
        <ul>
          {products.map((p) => (
            <li key={p.productId}>
              {p.productNm} (현재 {p.stockQty})
              <input type="number" onChange={(e) => this.handleChange(p.productId, e.target.value)} />
              <button type="button" onClick={() => this.handleSave(p.productId)}>입고</button>
            </li>
          ))}
        </ul>
      </section>
    );
  }
}

export default ProductStock;
