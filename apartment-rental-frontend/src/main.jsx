import React from 'react'
import { createRoot } from 'react-dom/client'
import { HashRouter, Link, Route, Routes } from 'react-router-dom'
import { ApartmentDetail, Guide, Home, Saved } from './public'
import { Authentication, ProfilePage } from './account'
import { ContractDetail, TenantContracts, TenantInvoices } from './contracts'
import { OwnerBilling, OwnerContracts, OwnerDashboard, OwnerListings, OwnerProperties, OwnerTenants } from './owner'
import { DemoProvider, Empty, PublicLayout, RoleLayout, ScrollToTop } from './shared'
import './styles.css'

createRoot(document.getElementById('root')).render(
  <React.StrictMode><HashRouter useTransitions={false}><DemoProvider><ScrollToTop /><Routes>
    <Route element={<PublicLayout />}>
      <Route index element={<Home />} />
      <Route path="can-ho/:id" element={<ApartmentDetail />} />
      <Route path="huong-dan" element={<Guide />} />
      <Route path="da-luu" element={<Saved />} />
      <Route path="dang-nhap" element={<Authentication key="login" />} />
      <Route path="dang-ky" element={<Authentication key="register" mode="register" />} />
      <Route path="quen-mat-khau" element={<Authentication key="reset" mode="reset" />} />
      <Route path="*" element={<section className="frame inner-page"><Empty title="Không tìm thấy trang"><Link className="button primary" to="/">Về trang chủ</Link></Empty></section>} />
    </Route>
    <Route path="tenant" element={<RoleLayout role="tenant" />}>
      <Route index element={<TenantContracts />} />
      <Route path="hop-dong/:id" element={<ContractDetail role="tenant" />} />
      <Route path="hoa-don" element={<TenantInvoices />} />
      <Route path="ho-so" element={<ProfilePage />} />
    </Route>
    <Route path="owner" element={<RoleLayout role="owner" />}>
      <Route index element={<OwnerDashboard />} />
      <Route path="can-ho" element={<OwnerProperties />} />
      <Route path="tin-dang" element={<OwnerListings />} />
      <Route path="hop-dong" element={<OwnerContracts />} />
      <Route path="hop-dong/:id" element={<ContractDetail role="owner" />} />
      <Route path="khach-thue" element={<OwnerTenants />} />
      <Route path="hoa-don" element={<OwnerBilling />} />
      <Route path="ho-so" element={<ProfilePage />} />
    </Route>
  </Routes></DemoProvider></HashRouter></React.StrictMode>,
)
