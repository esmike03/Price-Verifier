package com.priceverifier;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Device-side promo management for one product: a list of its promos (server
 * ones are read-only) and a form to add, edit or delete device promos.
 */
final class PromoEditor {
    private static final Promo.Type[] TYPES = {
            Promo.Type.PERCENT, Promo.Type.AMOUNT, Promo.Type.FIXED, Promo.Type.BUY_TAKE};
    private static final int DEFAULT_DAYS = 7;

    private final Activity activity;
    private final ProductDatabase database;

    PromoEditor(Activity activity, ProductDatabase database) {
        this.activity = activity;
        this.database = database;
    }

    void showList(Product product, Runnable onChanged) {
        List<Promo> promos = database.promosFor(product.barcode);
        String today = Promo.today();
        CharSequence[] items = new CharSequence[promos.size()];
        for (int i = 0; i < promos.size(); i++) {
            Promo promo = promos.get(i);
            StringBuilder text = new StringBuilder(promo.summary(product.priceCents));
            if (!promo.isActiveOn(today)) {
                boolean ended = !promo.endDate.isEmpty() && promo.endDate.compareTo(today) < 0;
                text.append(" (").append(activity.getString(ended
                        ? R.string.promo_status_ended : R.string.promo_status_scheduled)).append(')');
            }
            if (promo.isServer()) {
                text.append(Promo.DOT).append(activity.getString(R.string.promo_from_server));
            }
            items[i] = text;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.promo_list_title, product.name))
                .setPositiveButton(R.string.promo_add, (d, w) -> showForm(product, null, onChanged))
                .setNegativeButton(R.string.close, null);
        if (promos.isEmpty()) {
            builder.setMessage(R.string.promo_list_empty);
        } else {
            builder.setItems(items, (d, which) -> {
                Promo promo = promos.get(which);
                if (promo.isServer()) {
                    Toast.makeText(activity, R.string.promo_server_readonly, Toast.LENGTH_LONG).show();
                } else {
                    showForm(product, promo, onChanged);
                }
            });
        }
        builder.show();
    }

    private void showForm(Product product, Promo existing, Runnable onChanged) {
        View form = LayoutInflater.from(activity).inflate(R.layout.dialog_promo_form, null);
        Spinner labelSpinner = form.findViewById(R.id.promoLabelSpinner);
        Spinner typeSpinner = form.findViewById(R.id.promoTypeSpinner);
        EditText value = form.findViewById(R.id.promoValue);
        View buyTakeRow = form.findViewById(R.id.promoBuyTakeRow);
        EditText buy = form.findViewById(R.id.promoBuy);
        EditText take = form.findViewById(R.id.promoTake);
        Button startButton = form.findViewById(R.id.promoStartButton);
        Button endButton = form.findViewById(R.id.promoEndButton);
        TextView preview = form.findViewById(R.id.promoPreview);

        labelSpinner.setAdapter(spinnerAdapter(
                activity.getString(R.string.promo_label_promo),
                activity.getString(R.string.promo_label_special)));
        typeSpinner.setAdapter(spinnerAdapter(
                activity.getString(R.string.promo_type_percent),
                activity.getString(R.string.promo_type_amount),
                activity.getString(R.string.promo_type_fixed),
                activity.getString(R.string.promo_type_buy_take)));

        // start/end are yyyy-MM-dd, held in arrays so the date pickers can update them.
        String[] start = {Promo.today()};
        String[] end = {addDays(Promo.today(), DEFAULT_DAYS)};
        if (existing != null) {
            labelSpinner.setSelection(existing.isSpecial() ? 1 : 0);
            typeSpinner.setSelection(indexOf(existing.type));
            value.setText(existing.valueText());
            if (existing.type == Promo.Type.BUY_TAKE) {
                buy.setText(String.valueOf(existing.buyQty));
                take.setText(String.valueOf(existing.takeQty));
            }
            start[0] = existing.startDate;
            end[0] = existing.endDate;
        } else {
            buy.setText("1");
            take.setText("1");
        }
        if (hasServerPromo(product)) {
            form.findViewById(R.id.promoServerNote).setVisibility(View.VISIBLE);
        }

        Runnable refresh = () -> {
            Promo.Type type = TYPES[typeSpinner.getSelectedItemPosition()];
            boolean buyTake = type == Promo.Type.BUY_TAKE;
            value.setVisibility(buyTake ? View.GONE : View.VISIBLE);
            buyTakeRow.setVisibility(buyTake ? View.VISIBLE : View.GONE);
            value.setHint(type == Promo.Type.PERCENT ? R.string.promo_value_percent_hint
                    : type == Promo.Type.AMOUNT ? R.string.promo_value_amount_hint
                    : R.string.promo_value_fixed_hint);
            startButton.setText(activity.getString(R.string.promo_start, dateLabel(start[0])));
            endButton.setText(activity.getString(R.string.promo_end, dateLabel(end[0])));
            Promo draft = build(product, existing, labelSpinner, typeSpinner, value, buy, take,
                    start[0], end[0]);
            preview.setText(previewText(product, draft));
        };

        typeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                refresh.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refresh.run();
            }
        };
        value.addTextChangedListener(watcher);
        buy.addTextChangedListener(watcher);
        take.addTextChangedListener(watcher);
        startButton.setOnClickListener(v -> pickDate(start, refresh));
        endButton.setOnClickListener(v -> pickDate(end, refresh));
        refresh.run();

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(existing == null ? R.string.promo_new_title : R.string.promo_edit_title)
                .setView(form)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null);
        if (existing != null) {
            builder.setNeutralButton(R.string.delete, (d, w) -> {
                database.deleteDevicePromo(existing.id);
                Toast.makeText(activity, R.string.promo_deleted, Toast.LENGTH_SHORT).show();
                onChanged.run();
            });
        }
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    Promo promo = build(product, existing, labelSpinner, typeSpinner, value, buy,
                            take, start[0], end[0]);
                    if (promo == null) {
                        Toast.makeText(activity, R.string.promo_invalid, Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (promo.reducesPrice() && promo.promoPriceCents(product.priceCents) >= product.priceCents) {
                        Toast.makeText(activity, R.string.promo_not_lower, Toast.LENGTH_LONG).show();
                        return;
                    }
                    database.saveDevicePromo(promo);
                    dialog.dismiss();
                    Toast.makeText(activity, R.string.promo_saved, Toast.LENGTH_SHORT).show();
                    onChanged.run();
                }));
        dialog.show();
    }

    private boolean hasServerPromo(Product product) {
        for (Promo promo : database.promosFor(product.barcode)) {
            if (promo.isServer()) {
                return true;
            }
        }
        return false;
    }

    private static Promo build(Product product, Promo existing, Spinner labelSpinner,
                               Spinner typeSpinner, EditText value, EditText buy, EditText take,
                               String start, String end) {
        Promo.Type type = TYPES[typeSpinner.getSelectedItemPosition()];
        Promo parsed = PromoImporter.fromRow(product.barcode,
                labelSpinner.getSelectedItemPosition() == 1 ? Promo.LABEL_SPECIAL : Promo.LABEL_PROMO,
                Promo.typeKey(type), value.getText().toString(), buy.getText().toString(),
                take.getText().toString(), start, end, Promo.SOURCE_DEVICE);
        if (parsed == null || existing == null) {
            return parsed;
        }
        // Keep the row id so saving updates this promo instead of adding another.
        return new Promo(existing.id, parsed.barcode, parsed.source, parsed.label, parsed.type,
                parsed.value, parsed.buyQty, parsed.takeQty, parsed.startDate, parsed.endDate);
    }

    private String previewText(Product product, Promo draft) {
        if (draft == null) {
            return activity.getString(R.string.promo_preview_empty);
        }
        String regular = PriceFormatter.format(product.priceCents);
        if (draft.reducesPrice()) {
            return regular + "  →  " + PriceFormatter.format(draft.promoPriceCents(product.priceCents));
        }
        return draft.dealText(product.priceCents) + Promo.DOT + regular;
    }

    private void pickDate(String[] target, Runnable onPicked) {
        Calendar calendar = Calendar.getInstance();
        try {
            calendar.setTime(new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    .parse(target[0].isEmpty() ? Promo.today() : target[0]));
        } catch (Exception ignored) {
        }
        new DatePickerDialog(activity, (view, year, month, day) -> {
            target[0] = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day);
            onPicked.run();
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)).show();
    }

    private static String dateLabel(String isoDate) {
        return isoDate.isEmpty() ? "—" : Promo.displayDate(isoDate);
    }

    private static String addDays(String isoDate, int days) {
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(format.parse(isoDate));
            calendar.add(Calendar.DAY_OF_MONTH, days);
            return format.format(calendar.getTime());
        } catch (Exception ex) {
            return isoDate;
        }
    }

    private static int indexOf(Promo.Type type) {
        for (int i = 0; i < TYPES.length; i++) {
            if (TYPES[i] == type) {
                return i;
            }
        }
        return 0;
    }

    private ArrayAdapter<String> spinnerAdapter(String... labels) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }
}
