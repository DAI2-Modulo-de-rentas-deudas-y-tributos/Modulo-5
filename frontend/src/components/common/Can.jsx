import usePermission from "../../hooks/usePermission.js";

export default function Can({ permission, children, fallback = null }) {
  const { can } = usePermission();
  return can(permission) ? children : fallback;
}
