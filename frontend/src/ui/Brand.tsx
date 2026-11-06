import { ShieldCheck } from 'lucide-react';
import { Link } from 'react-router-dom';

export function Brand() {
  return (
    <Link to="/" className="brand" aria-label="SecureShare — главная">
      <span className="brand__mark">
        <ShieldCheck size={23} aria-hidden="true" />
      </span>
      <span>SecureShare</span>
    </Link>
  );
}
