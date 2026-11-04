package com.priceverifier;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ProductListActivity extends Activity {
    private static final int PAGE_SIZE = 100;

    private ProductDatabase database;
    private EditText searchInput;
    private TextView pageStatus;
    private Button previousButton;
    private Button nextButton;
    private ProductAdapter adapter;
    private int offset;
    private int total;
    private String query = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_product_list);
        database = new ProductDatabase(this);
        searchInput = findViewById(R.id.productSearchInput);
        pageStatus = findViewById(R.id.productPageStatus);
        previousButton = findViewById(R.id.productPreviousButton);
        nextButton = findViewById(R.id.productNextButton);
        ListView list = findViewById(R.id.productList);
        adapter = new ProductAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) ->
                showEditDialog(adapter.getItem(position)));

        findViewById(R.id.productListBackButton).setOnClickListener(v -> finish());
        findViewById(R.id.productSearchButton).setOnClickListener(v -> applySearch());
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                applySearch();
                return true;
            }
            return false;
        });
        previousButton.setOnClickListener(v -> {
            offset = Math.max(0, offset - PAGE_SIZE);
            loadPage();
        });
        nextButton.setOnClickListener(v -> {
            if (offset + PAGE_SIZE < total) {
                offset += PAGE_SIZE;
                loadPage();
            }
        });
        loadPage();
    }

    @Override
    protected void onDestroy() {
        database.close();
        super.onDestroy();
    }

    private void applySearch() {
        query = Product.clean(searchInput.getText().toString());
        offset = 0;
        loadPage();
    }

    private void loadPage() {
        total = database.countProducts(query);
        if (offset >= total && offset > 0) {
            offset = Math.max(0, ((total - 1) / PAGE_SIZE) * PAGE_SIZE);
        }
        adapter.replace(database.listProducts(query, PAGE_SIZE, offset));
        if (total == 0) {
            pageStatus.setText(R.string.product_page_empty);
        } else {
            pageStatus.setText(getString(R.string.product_page_status,
                    offset + 1, Math.min(offset + PAGE_SIZE, total), total));
        }
        previousButton.setEnabled(offset > 0);
        nextButton.setEnabled(offset + PAGE_SIZE < total);
    }

    private void showEditDialog(Product current) {
        View form = LayoutInflater.from(this).inflate(R.layout.dialog_edit_product, null);
        EditText barcode = form.findViewById(R.id.editProductBarcode);
        EditText name = form.findViewById(R.id.editProductName);
        EditText uom = form.findViewById(R.id.editProductUom);
        EditText category = form.findViewById(R.id.editProductCategory);
        EditText price = form.findViewById(R.id.editProductPrice);
        EditText itemCode = form.findViewById(R.id.editProductItemCode);
        barcode.setText(current.barcode);
        name.setText(current.name);
        uom.setText(current.uom);
        category.setText(current.category);
        price.setText(formatPriceInput(current.priceCents));
        itemCode.setText(current.itemCode);

        Button promos = form.findViewById(R.id.editProductPromos);
        Runnable refreshPromos = () -> promos.setText(getString(R.string.promo_button,
                database.promosFor(current.barcode).size()));
        refreshPromos.run();
        promos.setOnClickListener(v ->
                new PromoEditor(this, database).showList(current, refreshPromos));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.edit_product_title)
                .setView(form)
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.delete, null)
                .setPositiveButton(R.string.save, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String nextBarcode = Product.normalizeBarcode(barcode.getText().toString());
                String nextName = Product.clean(name.getText().toString());
                Long priceCents = ProductImporter.parsePriceCents(price.getText().toString());
                if (nextBarcode.isEmpty() || nextName.isEmpty() || priceCents == null || priceCents < 0) {
                    Toast.makeText(this, R.string.manual_product_invalid, Toast.LENGTH_SHORT).show();
                    return;
                }
                database.upsertProduct(new Product(nextBarcode, nextName, priceCents,
                        uom.getText().toString(), category.getText().toString(),
                        itemCode.getText().toString(), System.currentTimeMillis()));
                if (!current.barcode.equals(nextBarcode)) {
                    database.deleteProduct(current.barcode);
                }
                dialog.dismiss();
                loadPage();
                Toast.makeText(this, R.string.manual_product_updated, Toast.LENGTH_SHORT).show();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                    confirmDelete(current, dialog));
        });
        dialog.show();
    }

    private void confirmDelete(Product product, AlertDialog editDialog) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_product_title)
                .setMessage(getString(R.string.delete_product_message, product.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    database.deleteProduct(product.barcode);
                    editDialog.dismiss();
                    loadPage();
                })
                .show();
    }

    private static String formatPriceInput(long cents) {
        return String.format(Locale.US, "%d.%02d", cents / 100, Math.abs(cents % 100));
    }

    private final class ProductAdapter extends BaseAdapter {
        private final List<Product> products = new ArrayList<>();

        void replace(List<Product> next) {
            products.clear();
            products.addAll(next);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return products.size();
        }

        @Override
        public Product getItem(int position) {
            return products.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(ProductListActivity.this)
                        .inflate(R.layout.row_product, parent, false);
            }
            Product product = getItem(position);
            ((TextView) row.findViewById(R.id.productRowName)).setText(product.name);
            ((TextView) row.findViewById(R.id.productRowBarcode)).setText(product.barcode);
            String meta = product.itemCode;
            if (!product.category.isEmpty()) {
                meta = meta.isEmpty() ? product.category : meta + "  |  " + product.category;
            }
            if (!product.uom.isEmpty()) {
                meta = meta.isEmpty() ? product.uom : meta + "  |  " + product.uom;
            }
            ((TextView) row.findViewById(R.id.productRowMeta)).setText(meta);
            ((TextView) row.findViewById(R.id.productRowPrice)).setText(
                    formatPriceInput(product.priceCents));
            return row;
        }
    }
}
