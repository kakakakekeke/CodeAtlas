import React, { useEffect, useState } from 'react';
import { Category, fetchCategoryTree } from '../api/productApi';

// named export: 재귀 렌더 컴포넌트
export function CategoryNode({ node }: { node: Category }): JSX.Element {
  return (
    <li>
      {node.categoryNm}
      {node.children.length > 0 && (
        <ul>
          {node.children.map((child) => (
            <CategoryNode key={child.categoryId} node={child} />
          ))}
        </ul>
      )}
    </li>
  );
}

/**
 * 카테고리 트리 (default export).
 */
export default function CategoryTree(): JSX.Element {
  const [roots, setRoots] = useState<Category[]>([]);

  useEffect(() => {
    fetchCategoryTree().then(setRoots);
  }, []);

  return (
    <ul className="category-tree">
      {roots.map((root) => (
        <CategoryNode key={root.categoryId} node={root} />
      ))}
    </ul>
  );
}
